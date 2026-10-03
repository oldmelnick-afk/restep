package com.rocketglasses.soberyobratno

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

class VoiceEngine(
    private val context: Context,
    private val onCommand: (String) -> Unit,
    private val onNote: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val models = mutableMapOf<String, Model>()
    @Volatile private var worker: Thread? = null
    @Volatile private var cameraSuspended = false
    private val running = AtomicBoolean(false)
    @Volatile var language = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        .getString("language", "ru") ?: "ru"
        private set
    @Volatile private var desiredRunning = false
    @Volatile private var loading = false
    @Volatile private var noteMode = false
    @Volatile private var muted = false
    @Volatile private var draft = ""
    @Volatile private var partial = ""
    @Volatile private var submitRequested = false

    fun switchLanguage(): String {
        language = if (language == "ru") "en" else "ru"
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putString("language", language).apply()
        return language
    }

    @Synchronized fun start() {
        desiredRunning = true
        if (cameraSuspended || running.get() || worker != null || loading) return
        if (models.size == 2) {
            startWorker(models.getValue("ru"), models.getValue("en"))
            return
        }
        loading = true
        val missing = if (!models.containsKey("ru")) "ru" else "en"
        onStatus("LOADING RU / EN SPEECH")
        val selected = "model-$missing"
        StorageService.unpack(context.applicationContext, selected, selected,
            { loaded ->
                synchronized(this) {
                    if (cameraSuspended) loaded.close() else models[missing] = loaded
                    loading = false
                    if (desiredRunning) start()
                }
            }, { error ->
                loading = false
                onStatus("SPEECH: ${error.message ?: "ERROR"}")
            })
    }

    private fun startWorker(ru: Model, en: Model) {
        running.set(true)
        worker = Thread { loop(ru, en) }.apply { name = "MemoryVoice"; start() }
    }

    fun expectNote(initialText: String = "") { draft = initialText; partial = ""; submitRequested = false; noteMode = true }
    fun cancelNote() { noteMode = false; draft = ""; partial = ""; submitRequested = false }
    fun currentDraft(): String = listOf(draft, partial).filter { it.isNotBlank() }.joinToString(" ")
    fun submitNote(): Boolean {
        if (!noteMode || !running.get()) return false
        submitRequested = true
        return true
    }
    fun mute(value: Boolean) { muted = value }

    /** Release native model/decoder memory before the camera allocates its capture pipeline. */
    fun suspendForCamera(onReady: (Boolean) -> Unit) {
        cameraSuspended = true
        running.set(false)
        val previous = worker
        previous?.interrupt()
        Thread {
            previous?.join(6000)
            val deadline = SystemClock.uptimeMillis() + 10000
            while (loading && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            val released = synchronized(this) {
                if (previous?.isAlive == true || loading) false
                else {
                    models.values.forEach { it.close() }
                    models.clear()
                    true
                }
            }
            Log.i("MemoryVoice", "Speech memory released for camera: $released")
            onReady(released)
        }.apply { name = "CameraMemoryRelease"; start() }
    }

    @Synchronized fun resumeAfterCamera() {
        cameraSuspended = false
        if (desiredRunning) start()
    }

    fun close() {
        desiredRunning = false
        suspendForCamera { }
    }

    private fun loop(ru: Model, en: Model) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val ruCommands = JSONArray().apply {
            put("сделать фото"); put("сделай фото"); put("сфотографировать")
            put("[unk]")
        }.toString()
        val enCommands = JSONArray().apply {
            put("take photo"); put("take a photo"); put("capture photo")
            put("[unk]")
        }.toString()
        val ruSendCommands = JSONArray(VoiceCommands.russianSend.toList() + "[unk]").toString()
        val enSendCommands = JSONArray(VoiceCommands.englishSend.toList() + "[unk]").toString()
        val ruCommandRecognizer = Recognizer(ru, 16000f, ruCommands)
        val enCommandRecognizer = Recognizer(en, 16000f, enCommands)
        ruCommandRecognizer.setWords(true)
        enCommandRecognizer.setWords(true)
        var noteRecognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "Microphone permission missing" }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "Microphone unavailable" }
            record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not open" }
            record.startRecording()
            onStatus(if (noteMode) "NOTE READY. ОТПРАВИТЬ / SEND" else "СДЕЛАТЬ ФОТО / TAKE PHOTO")
            val buffer = ShortArray(800)
            var wasNote = false
            var lastPartialAt = 0L
            while (running.get()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (muted) continue
                val nowNote = noteMode
                if (nowNote != wasNote) {
                    partial = ""
                    ruCommandRecognizer.reset(); enCommandRecognizer.reset()
                    ruCommandRecognizer.setGrammar(if (nowNote) ruSendCommands else ruCommands)
                    enCommandRecognizer.setGrammar(if (nowNote) enSendCommands else enCommands)
                    noteRecognizer?.close()
                    noteRecognizer = if (nowNote) Recognizer(if (language == "ru") ru else en, 16000f) else null
                    wasNote = nowNote
                }
                if (nowNote) {
                    val recognizer = noteRecognizer ?: continue
                    if (submitRequested) {
                        val finalText = JSONObject(recognizer.finalResult).optString("text").trim()
                        val text = finalText.ifBlank { partial }
                        submitRequested = false
                        finishNote(text)
                        continue
                    }
                    val ruDone = ruCommandRecognizer.acceptWaveForm(buffer, n)
                    val enDone = enCommandRecognizer.acceptWaveForm(buffer, n)
                    val ruSend = ruDone && recognizedSend(ruCommandRecognizer.result, "ru")
                    val enSend = enDone && recognizedSend(enCommandRecognizer.result, "en")
                    val commandSend = ruSend || enSend
                    val noteDone = recognizer.acceptWaveForm(buffer, n)
                    if (commandSend) {
                        val finalText = JSONObject(if (noteDone) recognizer.result else recognizer.finalResult)
                            .optString("text").trim()
                        Log.i("MemoryVoice", "Send command detected: ${if (enSend) "en" else "ru"}")
                        finishNote(finalText.ifBlank { partial }, commandConfirmed = true)
                        continue
                    }
                    if (!noteDone) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPartialAt >= 200) {
                            partial = JSONObject(recognizer.partialResult).optString("partial")
                            lastPartialAt = now
                        }
                        continue
                    }
                    partial = ""
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty()) {
                        val submission = VoiceCommands.submission(text)
                        if (submission.send) finishNote(text)
                        else {
                            draft = listOf(draft, text).filter { it.isNotBlank() }.joinToString(" ")
                            onStatus("NOTE READY. ОТПРАВИТЬ / SEND")
                        }
                    }
                } else {
                    val ruDone = ruCommandRecognizer.acceptWaveForm(buffer, n)
                    val enDone = enCommandRecognizer.acceptWaveForm(buffer, n)
                    val ruText = if (ruDone) JSONObject(ruCommandRecognizer.result).optString("text").trim() else ""
                    val enText = if (enDone) JSONObject(enCommandRecognizer.result).optString("text").trim() else ""
                    val recognized = when {
                        ruText in setOf("сделать фото", "сделай фото", "сфотографировать") -> "ru" to ruText
                        enText in setOf("take photo", "take a photo", "capture photo") -> "en" to enText
                        else -> null
                    }
                    if (recognized != null) {
                        language = recognized.first
                        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
                            .putString("language", language).apply()
                        ruCommandRecognizer.reset(); enCommandRecognizer.reset()
                        onCommand(recognized.second)
                    }
                }
            }
        } catch (e: Exception) {
            onStatus("SPEECH: ${e.message ?: "ERROR"}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            ruCommandRecognizer.close(); enCommandRecognizer.close(); noteRecognizer?.close()
            running.set(false)
            worker = null
        }
    }

    private fun recognizedSend(json: String, language: String): Boolean {
        val result = JSONObject(json)
        val words = result.optJSONArray("result") ?: return false
        if (words.length() == 0) return false
        val confidence = (0 until words.length()).minOf { words.getJSONObject(it).optDouble("conf", 0.0) }
        return VoiceCommands.isSendCommand(result.optString("text"), language, confidence)
    }

    private fun finishNote(text: String, commandConfirmed: Boolean = false) {
        val combined = listOf(draft, text).filter { it.isNotBlank() }.joinToString(" ")
        draft = VoiceCommands.submission(combined, commandConfirmed).note
        partial = ""
        noteMode = false
        onNote(draft)
    }

    fun stop() {
        desiredRunning = false
        running.set(false)
        worker?.interrupt()
        worker?.join(2000)
        // A live native decoder must finish before another worker can use its models.
    }
}
