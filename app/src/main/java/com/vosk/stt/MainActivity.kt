package com.vosk.stt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.vosk.stt.databinding.ActivityMainBinding
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false
    private var isModelLoaded = false
    private val hotwords = mutableListOf<String>()

    private val modelList = listOf(
        ModelInfo("vosk-model-small-en-us-0.15", "英文（小）", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        ModelInfo("vosk-model-small-cn-0.22", "中文（小）", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
        ModelInfo("vosk-model-en-us-0.22", "英文（大）", "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"),
        ModelInfo("vosk-model-cn-0.22", "中文（大）", "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip")
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "麦克风权限已获取", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要麦克风权限才能进行语音识别", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Vosk logging
        LibVosk.setLogLevel(LogLevel.INFO)

        setupUI()
        checkPermissions()
        checkExistingModel()
    }

    // ── UI Setup ────────────────────────────────────────────

    private fun setupUI() {
        // Model spinner
        val modelNames = modelList.map { "${it.name} (${it.label})" }
        binding.modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modelNames
        )

        // Mic button
        binding.micButton.setOnClickListener {
            when {
                !isModelLoaded -> {
                    Toast.makeText(this, "请先下载语音模型", Toast.LENGTH_SHORT).show()
                }
                !checkPermission() -> {
                    requestPermission()
                }
                isListening -> stopRecognition()
                else -> startRecognition()
            }
        }

        // Download button
        binding.downloadModelButton.setOnClickListener { downloadModel() }

        // Add hotword
        binding.addHotwordButton.setOnClickListener {
            val word = binding.hotwordInput.text.toString().trim()
            if (word.isNotEmpty()) {
                if (!hotwords.contains(word)) {
                    hotwords.add(word)
                    updateHotwordList()
                    binding.hotwordInput.text?.clear()
                    Toast.makeText(this, "热词「$word」已添加", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "热词「$word」已存在", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Clear hotwords
        binding.clearHotwordsButton.setOnClickListener {
            hotwords.clear()
            updateHotwordList()
            Toast.makeText(this, "所有热词已清空", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Permissions ─────────────────────────────────────────

    private fun checkPermissions() {
        if (!checkPermission()) requestPermission()
    }

    private fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ── Model Management ────────────────────────────────────

    private fun checkExistingModel() {
        val modelsDir = getModelDir()
        if (modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            if (!modelDirs.isNullOrEmpty()) {
                loadModel(modelDirs.first().absolutePath)
                return
            }
        }

        // Try bundled model from APK assets
        val bundledModel = "vosk-model-small-en-us-0.15"
        try {
            assets.list(bundledModel)?.let { files ->
                if (files.isNotEmpty()) {
                    setStatus("正在解压内置模型…", gray = false)
                    modelsDir.mkdirs()
                    val targetDir = File(modelsDir, bundledModel)
                    StorageService.unpack(this, bundledModel, targetDir.absolutePath,
                        object : StorageService.Callback<Model> {
                            override fun onComplete(model: Model) {
                                this@MainActivity.model = model
                                runOnUiThread {
                                    isModelLoaded = true
                                    binding.micButton.isEnabled = true
                                    setStatus("✅ 模型已就绪，点击麦克风开始识别", green = true)
                                    Toast.makeText(this@MainActivity, "内置模型加载成功", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onError(e: Exception) {
                                runOnUiThread {
                                    setStatus("解压失败，请手动下载: ${e.message}", red = true)
                                    binding.downloadSection.isVisible = true
                                }
                            }
                        })
                    return
                }
            }
        } catch (_: Exception) {
            // No bundled model in assets
        }

        binding.statusText.text = "请下载语音模型"
        binding.downloadSection.isVisible = true
    }

    private fun loadModel(path: String) {
        setStatus("正在加载模型…", gray = false)
        thread {
            try {
                val m = Model(path)
                model = m
                runOnUiThread {
                    isModelLoaded = true
                    binding.micButton.isEnabled = true
                    setStatus("✅ 模型已就绪，点击麦克风开始识别", green = true)
                    Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("❌ 模型加载失败: ${e.message}", red = true)
                    Toast.makeText(this, "模型加载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadModel() {
        val idx = binding.modelSpinner.selectedItemPosition
        val info = modelList[idx]

        setStatus("⏳ 正在下载 ${info.name} …", gray = false)
        binding.downloadModelButton.isEnabled = false
        binding.progressBar.isVisible = true
        binding.progressBar.progress = 0

        thread {
            try {
                val modelsDir = getModelDir()
                modelsDir.mkdirs()

                // Download zip
                val zipFile = File(cacheDir, "${info.name}.zip")
                val url = URL(info.url)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()

                val totalLen = conn.contentLengthLong
                val input = conn.inputStream
                val output = FileOutputStream(zipFile)
                val buf = ByteArray(8192)
                var read: Int
                var downloaded = 0L

                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (totalLen > 0) {
                        val pct = ((downloaded * 100) / totalLen).toInt()
                        runOnUiThread { binding.progressBar.progress = pct }
                    }
                }
                output.close()
                input.close()
                conn.disconnect()

                runOnUiThread { setStatus("⏳ 正在解压模型…", gray = false) }

                // Unzip — the zip contains a top-level folder named info.name
                val targetDir = File(modelsDir, info.name)
                targetDir.mkdirs()
                unzip(zipFile, targetDir)

                zipFile.delete()

                runOnUiThread {
                    binding.progressBar.isVisible = false
                    binding.downloadModelButton.isEnabled = true
                }

                loadModel(targetDir.absolutePath)
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("❌ 下载失败: ${e.message}", red = true)
                    binding.downloadModelButton.isEnabled = true
                    binding.progressBar.isVisible = false
                    Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val zis = ZipInputStream(zipFile.inputStream())
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            // Handle both flat and nested zip structures.
            // Some Vosk zips have top-level dir, some don't.
            // We strip the top-level dir if present.
            val parts = name.split("/")
            val relativePath = if (parts.size > 1 && parts[0] == targetDir.name) {
                parts.drop(1).joinToString("/")
            } else {
                name
            }
            if (relativePath.isBlank()) {
                entry = zis.nextEntry
                continue
            }

            val outFile = File(targetDir, relativePath)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    zis.copyTo(fos)
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
    }

    private fun getModelDir(): File = File(filesDir, "models")

    // ── Recognition ─────────────────────────────────────────

    private fun startRecognition() {
        val m = model ?: return
        try {
            val recognizer: Recognizer = if (hotwords.isNotEmpty()) {
                Recognizer(m, 16000.0f, buildHotwordGrammar())
            } else {
                Recognizer(m, 16000.0f)
            }

            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)

            isListening = true
            binding.micButton.text = "停止识别"
            binding.micButton.setIconResource(android.R.drawable.ic_media_pause)
            setStatus("🎤 正在聆听…", green = true)
            binding.recognizedText.text = ""
            binding.partialText.text = ""
        } catch (e: Exception) {
            Toast.makeText(this, "启动识别失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecognition() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
        isListening = false
        binding.micButton.text = "开始识别"
        binding.micButton.setIconResource(android.R.drawable.ic_btn_speak_now)
        setStatus("⏹ 识别已停止", gray = false)
    }

    private fun buildHotwordGrammar(): String {
        val sb = StringBuilder()
        sb.append("""{"hotwords" : {""")
        hotwords.forEachIndexed { i, word ->
            if (i > 0) sb.append(", ")
            sb.append("\"$word\" : 10.0")
        }
        sb.append("}}")
        return sb.toString()
    }

    // ── RecognitionListener ─────────────────────────────────

    override fun onResult(hypothesis: String?) {
        hypothesis?.let { parseAndAppend(it, "text") }
    }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis?.let { parseAndAppend(it, "text") }
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let { h ->
            try {
                val json = JSONObject(h)
                val partial = json.optString("partial", "")
                val text = json.optString("text", "")
                runOnUiThread {
                    if (partial.isNotEmpty()) binding.partialText.text = partial
                    if (text.isNotEmpty()) binding.recognizedText.append("$text\n")
                }
            } catch (_: Exception) {}
        }
    }

    override fun onError(e: Exception?) {
        runOnUiThread {
            setStatus("❌ 错误: ${e?.message}", red = true)
            isListening = false
            binding.micButton.text = "开始识别"
            binding.micButton.setIconResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    override fun onTimeout() {
        runOnUiThread {
            setStatus("⏱ 识别超时，请重试", gray = false)
            stopRecognition()
        }
    }

    private fun parseAndAppend(hypothesis: String, key: String) {
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString(key, "")
            if (text.isNotEmpty()) {
                runOnUiThread {
                    binding.recognizedText.append("$text\n")
                    binding.partialText.text = ""
                }
            }
        } catch (_: Exception) {}
    }

    // ── UI Helpers ──────────────────────────────────────────

    private fun setStatus(msg: String, green: Boolean = false, red: Boolean = false, gray: Boolean = true) {
        binding.statusText.text = msg
        binding.statusIndicator.setBackgroundResource(
            when {
                green -> R.drawable.circle_green
                red -> R.drawable.circle_red
                else -> R.drawable.circle_gray
            }
        )
    }

    private fun updateHotwordList() {
        binding.hotwordList.text = if (hotwords.isEmpty()) "暂无热词" else hotwords.joinToString("  ")
    }

    // ── Lifecycle ───────────────────────────────────────────

    override fun onDestroy() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        model?.close()
        super.onDestroy()
    }

    // ── Data class ──────────────────────────────────────────

    data class ModelInfo(val name: String, val label: String, val url: String)
}