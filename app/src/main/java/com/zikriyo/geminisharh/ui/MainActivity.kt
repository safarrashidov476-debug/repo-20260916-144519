package com.zikriyo.geminisharh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zikriyo.geminisharh.R
import com.zikriyo.geminisharh.api.AzureTtsClient
import com.zikriyo.geminisharh.api.EdgeTtsClient
import com.zikriyo.geminisharh.api.GeminiApiClient
import com.zikriyo.geminisharh.data.Prefs
import com.zikriyo.geminisharh.databinding.ActivityMainBinding
import com.zikriyo.geminisharh.util.MediaComposer
import com.zikriyo.geminisharh.util.OutputSaver
import com.zikriyo.geminisharh.util.TimestampParser
import com.zikriyo.geminisharh.util.TtsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var isProcessing = false
    private var videoMergeFailed = false

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = queryDisplayName(uri) ?: "video.mp4"
            binding.tvSelectedVideo.text = selectedName
            binding.btnStart.isEnabled = true
            appendLog("Tanlandi: $selectedName")
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled on demand */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)

        binding.btnPickVideo.setOnClickListener {
            ensurePermissions()
            pickVideo.launch("video/*")
        }

        binding.btnStart.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            startProcessing()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.open_settings))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermission.launch(needed.toTypedArray())
    }

    private fun startProcessing() {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        videoMergeFailed = false
        binding.btnStart.isEnabled = false
        binding.btnPickVideo.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.setText(R.string.status_uploading)
        binding.tvLog.text = "" // Yangi jarayon — eski loglarni tozalash
        appendLog("Jarayon boshlandi…")
        appendLog("Ovoz: ${prefs.selectedVoiceId}")
        appendLog("Model: ${prefs.selectedVisionModel}")

        lifecycleScope.launch {
            try {
                val resultDir = withContext(Dispatchers.IO) {
                    processVideo(uri, apiKey)
                }
                if (videoMergeFailed) {
                    binding.tvStatus.text = "Audio tayyor, lekin video yaratilmadi (log’ni ko‘ring)"
                    appendLog("DIQQAT: Video fayl yaratilmadi, faqat audio (narration.m4a) saqlandi.")
                    Toast.makeText(
                        this@MainActivity,
                        "Video yaratilmadi — faqat audio saqlandi. Log’dagi sababni tekshiring.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    binding.tvStatus.setText(R.string.success)
                    Toast.makeText(this@MainActivity, R.string.success, Toast.LENGTH_LONG).show()
                    // Muvaffaqiyatli tugagach — tanlangan video ilova ichidan ("nusxasi olib
                    // tashlanib") tozalanadi. Foydalanuvchi xohlasa, xuddi shu videoni
                    // qaytadan tanlab, yangidan (boshidan) qayta ishlatishi mumkin.
                    selectedUri = null
                    selectedName = ""
                    binding.tvSelectedVideo.text = "Video tanlanmagan"
                    binding.btnStart.isEnabled = false
                }
                appendLog("Tayyor! Papka: ${resultDir.absolutePath}")
            } catch (e: OutOfMemoryError) {
                binding.tvStatus.text = "Xotira yetmadi — video juda katta yoki rezolyutsiyasi baland"
                appendLog("XATO (OutOfMemoryError): Video/audio qayta ishlashda xotira yetmadi. " +
                    "Qisqaroq yoki pastroq sifatli video bilan urinib ko‘ring.")
            } catch (e: Throwable) {
                binding.tvStatus.text = getString(R.string.error_generic) + ": ${e.message}"
                appendLog("XATO (${e.javaClass.simpleName}): ${e.message}")
                e.printStackTrace()
            } finally {
                isProcessing = false
                binding.btnStart.isEnabled = selectedUri != null
                binding.btnPickVideo.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun processVideo(uri: Uri, apiKey: String): File {
        val delayMs = (prefs.requestDelaySec * 1000).toLong()
        val client = GeminiApiClient(apiKey, delayMs)
        val voiceId = prefs.selectedVoiceId
        val modelId = prefs.selectedVisionModel

        // Copy content URI to cache file
        updateStatus(R.string.status_uploading)
        val ext = selectedName.substringAfterLast('.', "mp4").lowercase()
        val mime = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/avi"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "wmv" -> "video/wmv"
            else -> "video/mp4"
        }
        val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw Exception("Videoni o‘qib bo‘lmadi")

        val sizeMb = cacheFile.length() / (1024.0 * 1024.0)
        appendLog("Fayl nusxalandi: %.1f MB, mime=$mime".format(sizeMb))
        if (sizeMb > 100) {
            appendLog("OGOHLANTIRISH: Fayl katta (>100 MB). Qisqaroq yoki siqilgan video sinab ko‘ring.")
        }

        // Upload + tahlil (Files API, FAILED bo'lsa inline fallback)
        updateStatus(R.string.status_analyzing)
        var rawText: String? = null
        try {
            val uploaded = client.uploadVideo(cacheFile, mime)
            appendLog("Yuklandi: ${uploaded.name}")
            appendLog("URI: ${uploaded.uri}")

            val active = client.waitUntilActive(uploaded.name)
            if (!active) throw Exception("Video serverda ACTIVE holatga o'tmadi (timeout)")
            appendLog("Video ACTIVE")

            rawText = client.generateTimedDescription(uploaded.uri, mime, "uz", modelId) { msg ->
                appendLog(msg)
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("FAILED") || msg.contains("qayta ishlanmadi")) {
                val maxInline = 18L * 1024 * 1024
                if (cacheFile.length() <= maxInline) {
                    appendLog("Files API FAILED - inline usul bilan qayta urinilmoqda...")
                    rawText = client.generateTimedDescriptionInline(cacheFile, mime, "uz", modelId) { m ->
                        appendLog(m)
                    }
                } else {
                    val mb = cacheFile.length() / 1024.0 / 1024.0
                    val mbStr = String.format("%.1f", mb)
                    throw Exception(
                        "Video serverda qayta ishlanmadi va fayl inline uchun katta " +
                            "($mbStr MB > 18 MB). Qisqaroq yoki H.264 MP4 qilib qayta kodlang. " +
                            "Asosiy xato: $msg"
                    )
                }
            } else {
                throw e
            }
        }
        val finalText = rawText ?: throw Exception("Gemini javobi bo'sh")
        appendLog("Gemini javobi olingan (${finalText.length} belgi)")

        val segments = TimestampParser.parse(finalText)
        if (segments.isEmpty()) {
            throw Exception("Vaqt kodlari topilmadi. Gemini javobi:\n$finalText")
        }
        appendLog("${segments.size} ta segment topildi")

        // Ishchi (vaqtinchalik) papka — barcha oraliq fayllar (srt, txt, audio, narration,
        // video nusxasi) shu yerda ishlanadi va jarayon oxirida BUTUNLAY o'chiriladi.
        // Foydalanuvchiga faqat tayyor video ko'rsatiladi.
        val workDir = File(cacheDir, "work_${System.currentTimeMillis()}")
        workDir.mkdirs()
        val outDir = workDir
        appendLog("Ishlanmoqda...")

        // Save SRT + TXT
        updateStatus(R.string.status_saving)
        File(outDir, "description.srt").writeText(TimestampParser.createSrt(segments))
        File(outDir, "description.txt").writeText(TimestampParser.createTxt(segments))
        File(outDir, "raw_gemini.txt").writeText(finalText)

        // TTS: Edge (har doim) → Azure → System
        // Fenrir/Charon/Puck/Aoede/Kore → Sardor yoki Madina ga map qilinadi
        updateStatus(R.string.status_tts)
        val audioDir = File(outDir, "audio_segments")
        audioDir.mkdirs()

        val edgeVoice = mapToEdgeVoice(voiceId)
        appendLog("TTS ovoz: tanlangan=$voiceId → Edge=$edgeVoice")

        val edge = EdgeTtsClient()
        val azureKey = prefs.azureKey
        val azure = if (azureKey.isNotBlank()) AzureTtsClient(azureKey, prefs.azureRegion) else null
        val systemTts = TtsHelper(this@MainActivity)

        var edgeOkCount = 0
        var azureOkCount = 0
        var systemOkCount = 0

        try {
            segments.forEachIndexed { idx, seg ->
                val audioFile = File(audioDir, "seg_%03d.mp3".format(idx))
                var ok = false

                runOnUiThread {
                    binding.tvStatus.text = getString(
                        R.string.status_tts_progress, idx + 1, segments.size
                    )
                }

                // 1. Edge — har doim (kalitsiz)
                try {
                    ok = edge.synthesizeToFile(seg.text, edgeVoice, audioFile)
                    if (ok) {
                        edgeOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [Edge]: OK")
                    } else {
                        appendLog("TTS ${idx + 1}/${segments.size} [Edge]: FAIL")
                    }
                } catch (e: Exception) {
                    appendLog("TTS ${idx + 1}/${segments.size} [Edge]: ${e.message}")
                }

                // 2. Azure
                if (!ok && azure != null) {
                    ok = azure.synthesizeToFile(seg.text, edgeVoice, audioFile)
                    if (ok) {
                        azureOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [Azure]: OK")
                    }
                }

                // 3. System TTS
                if (!ok) {
                    val wavFile = File(audioDir, "seg_%03d.wav".format(idx))
                    ok = systemTts.speakToFile(seg.text, wavFile)
                    if (ok) {
                        systemOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [System]: OK")
                    } else {
                        appendLog("TTS ${idx + 1}/${segments.size} [System]: FAIL")
                    }
                }

                Thread.sleep(200)
            }
        } finally {
            systemTts.shutdown()
        }

        appendLog("TTS yakun: Edge=$edgeOkCount, Azure=$azureOkCount, System=$systemOkCount")

        // Segment fayllar ro'yxati
        val segFiles = segments.indices.map { idx ->
            val mp3 = File(audioDir, "seg_%03d.mp3".format(idx))
            val wav = File(audioDir, "seg_%03d.wav".format(idx))
            when {
                mp3.exists() && mp3.length() > 0 -> mp3
                wav.exists() && wav.length() > 0 -> wav
                else -> mp3
            }
        }

        // Har bir segmentning HAQIQIY audio davomiyligini o'lchash va boshlanish
        // vaqtlarini shunga qarab moslashtirish: (1) segmentlar bir-biriga ustma-ust
        // tushmasin — har biri avvalgisi tugagandan keyin boshlansin; (2) hech bir
        // segment videoning o'zidan ancha uzoqroq davom etib, uni "ortda qoldirmasin".
        val videoDurationSec = MediaComposer.getMediaDurationSeconds(cacheFile)
        val gapSec = 0.25
        var prevEnd = 0.0
        var skippedCount = 0
        val adjustedSegments = mutableListOf<com.zikriyo.geminisharh.util.TimedSegment>()
        val adjustedSegFiles = mutableListOf<File>()
        val narrationWindows = mutableListOf<Pair<Double, Double>>()
        segments.forEachIndexed { idx, seg ->
            val f = segFiles.getOrNull(idx)
            if (f == null || !f.exists() || f.length() == 0L) return@forEachIndexed
            val dur = MediaComposer.getMediaDurationSeconds(f).let { if (it > 0) it else 2.5 }
            val start = maxOf(seg.seconds, prevEnd)
            if (videoDurationSec > 0 && start + 0.3 >= videoDurationSec) {
                skippedCount++
                return@forEachIndexed
            }
            adjustedSegments.add(seg.copy(seconds = start))
            adjustedSegFiles.add(f)
            narrationWindows.add(start to (start + dur))
            prevEnd = start + dur + gapSec
        }
        if (skippedCount > 0) {
            appendLog("$skippedCount ta segment video oxiriga sig'may qoldi, o'tkazib yuborildi")
        }
        if (prevEnd > videoDurationSec && videoDurationSec > 0) {
            appendLog(
                "Diqqat: sharh matni video uzunligidan (${"%.1f".format(videoDurationSec)}s) " +
                    "biroz uzunroq (${"%.1f".format(prevEnd)}s) — video tugagach ham " +
                    "bir necha soniya sharh davom etishi mumkin."
            )
        }

        // 1) Segmentlarni bitta narration.m4a ga yig'ish (moslashtirilgan, ustma-ust
        // tushmaydigan vaqtlar bilan)
        updateStatus(R.string.status_saving)
        appendLog("Narration yig'ilmoqda...")
        val narrationFile = File(outDir, "narration.m4a")
        var narrResult = MediaComposer.buildTimedNarration(adjustedSegments, adjustedSegFiles, narrationFile)
        if (!narrResult.ok) {
            appendLog("Timed narration: ${narrResult.log.take(200)}")
            appendLog("Oddiy concat urinilmoqda...")
            narrResult = MediaComposer.concatAudioSimple(adjustedSegFiles, narrationFile)
        }
        if (narrResult.ok && narrationFile.exists()) {
            appendLog("Narration OK: ${narrationFile.length() / 1024} KB")
        } else {
            appendLog("Narration FAIL: ${narrResult.log.take(400)}")
        }

        // 2) Original video + narration (original ovoz faqat sharh oralig'ida 40%)
        var finalPublicFile: File? = null
        if (narrResult.ok && narrationFile.exists() && narrationFile.length() > 0) {
            updateStatus(R.string.status_merging)
            appendLog("Video bilan birlashtirilmoqda (original ovoz faqat sharh vaqtida 40%)...")
            val finalVideo = File(outDir, "video_with_sharh.mp4")
            val videoCopy = File(outDir, "source_video.mp4")
            if (!videoCopy.exists()) {
                cacheFile.copyTo(videoCopy, overwrite = true)
            }
            val mergeResult = MediaComposer.mergeVideoWithNarration(
                videoFile = videoCopy,
                narrationFile = narrationFile,
                outputFile = finalVideo,
                originalVolume = 0.4,
                narrationVolume = 2.2,
                narrationWindows = narrationWindows
            )
            if (mergeResult.ok && finalVideo.exists()) {
                appendLog("Tayyor video: ${finalVideo.name} (${finalVideo.length() / 1024} KB)")
                finalPublicFile = finalVideo
            } else {
                appendLog("Birlashtirish FAIL: ${mergeResult.log.take(500)}")
                videoMergeFailed = true
            }
        } else {
            appendLog("Merge o'tkazib yuborildi — narration yo'q")
            videoMergeFailed = true
        }

        // Faqat tayyor video (narration/srt/txt/audio_segments EMAS) doimiy
        // "Yuklamalar/GeminiSharh" papkasiga (har doim BIR XIL papka) chiqariladi.
        val resultLocation = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GeminiSharh"
        )
        if (finalPublicFile != null) {
            val publishName = "sharh_${System.currentTimeMillis()}.mp4"
            val publishStaging = File(cacheDir, "publish_${System.currentTimeMillis()}")
            publishStaging.mkdirs()
            val staged = File(publishStaging, publishName)
            finalPublicFile.copyTo(staged, overwrite = true)
            try {
                val pub = OutputSaver.publishToDownloads(this@MainActivity, publishStaging, "GeminiSharh")
                appendLog("Yuklamalarda: $pub")
                appendLog("👉 Oching: Yuklamalar/GeminiSharh/$publishName")
                MediaScannerConnection.scanFile(
                    this@MainActivity,
                    arrayOf(staged.absolutePath),
                    null,
                    null
                )
            } catch (e: Exception) {
                appendLog("Publish: ${e.message}")
            } finally {
                publishStaging.deleteRecursively()
            }
        }

        // Barcha oraliq fayllarni (audio segmentlar, narration, srt/txt, video nusxasi) tozalash
        cacheFile.delete()
        workDir.deleteRecursively()

        return resultLocation
    }

    private fun updateStatus(resId: Int) {
        runOnUiThread { binding.tvStatus.setText(resId) }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    /**
     * Edge TTS faqat "xx-XX-NomNeural" formatidagi ovozlarni qo'llab-quvvatlaydi.
     * Gemini original ovozlari (Charon/Fenrir/Puck — erkak, Aoede/Kore — ayol)
     * mos O'zbekcha Edge ovozlariga (Sardor/Madina) moslashtiriladi.
     */
    private fun mapToEdgeVoice(voiceId: String): String {
        if (voiceId.contains("Neural")) return voiceId
        val maleGeminiVoices = setOf("Charon", "Fenrir", "Puck")
        val femaleGeminiVoices = setOf("Aoede", "Kore")
        return when (voiceId) {
            in maleGeminiVoices -> "uz-UZ-SardorNeural"
            in femaleGeminiVoices -> "uz-UZ-MadinaNeural"
            else -> "uz-UZ-SardorNeural"
        }
    }
}
