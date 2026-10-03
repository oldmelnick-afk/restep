package com.rocketglasses.soberyobratno

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: SessionStore
    private lateinit var camera: PhotoCapture
    private lateinit var hud: Hud
    private lateinit var voice: VoiceEngine
    private lateinit var server: TransferServer
    private lateinit var directSync: DirectSync
    private var pendingPhoto: ByteArray? = null
    private var status = "READY"
    private var syncPage = false
    private var capturing = false
    private var finishRequested = false
    private var exitAfterFinish = false
    private var quietMode = false
    private val hideMenu = Runnable {
        if (!syncPage && !capturing && pendingPhoto == null && store.current() != null) {
            quietMode = true
            hud.invalidate()
        }
    }
    private var pairCode = ""
    private var lastSwipeAt = 0L
    private var lastSwipeDirection = 0
    private var lastDoubleTapAt = 0L
    private val wifi by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SessionStore(this)
        if (store.sessions().length() == 0) store.newSession()
        camera = PhotoCapture(this)
        pairCode = getPreferences(MODE_PRIVATE).getString("pairCode", null) ?: run {
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            getPreferences(MODE_PRIVATE).edit().putString("pairCode", code).apply()
            code
        }
        hud = Hud()
        directSync = DirectSync(this) { hud.invalidate() }
        server = TransferServer(store, pairCode, { directSync.token },
            port = if (packageName.endsWith(".inputtest")) 8766 else 8765) { id ->
            val completed = java.util.concurrent.CountDownLatch(1)
            var allowed = false
            var failure: Exception? = null
            runOnUiThread {
                try {
                    val isCurrent = store.current()?.optString("id") == id
                    if (!isCurrent || (!capturing && pendingPhoto == null && !finishRequested)) {
                        store.deleteSession(id)
                        allowed = true
                        if (isCurrent) voice.cancelNote()
                        setStatus("DISASSEMBLY DELETED FROM IPHONE")
                        showMenu(false)
                    }
                } catch (e: Exception) { failure = e }
                finally { completed.countDown() }
            }
            check(completed.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Deletion timed out" }
            failure?.let { throw it }
            allowed
        }
        setContentView(hud)
        voice = VoiceEngine(this,
            onCommand = { command -> runOnUiThread { handleCommand(command) } },
            onNote = { note -> runOnUiThread { savePhoto(note) } },
            onStatus = { message -> runOnUiThread { setStatus(message) } })
        ensurePermissions()
        debugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugIntent(intent)
    }

    private fun debugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (intent?.getStringExtra("debugAction")) {
            "directSync" -> openDirectSync()
            "stopSync" -> { directSync.stop(); syncPage = false; showMenu() }
            "photo" -> takePhoto()
            "pendingPhotoFixture" -> if (packageName.endsWith(".inputtest")) {
                pendingPhoto = File(cacheDir, "input-fixture.jpg").readBytes()
                voice.expectNote("gesture test note")
                showMenu(false)
            }
            "doubleTap" -> {
                val id = android.view.InputDevice.getDeviceIds().firstOrNull {
                    android.view.InputDevice.getDevice(it)?.name?.startsWith("ROKID,PSOC-TP") == true
                } ?: return
                val now = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    dispatchKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0, id, 158))
                }
            }
            "cancelPhoto" -> if (!capturing) {
                pendingPhoto = null
                voice.cancelNote()
                voice.mute(false)
                setStatus("READY")
                showMenu()
            }
            "cameraSample" -> camera.take { result ->
                result.onSuccess {
                    store.addCameraSample(it)
                    Log.i("MemoryCamera", "Camera sample saved: ${it.size} bytes")
                    runOnUiThread { setStatus("CAMERA SAMPLE SAVED. SYNC TO IPHONE") }
                }.onFailure { Log.e("MemoryCamera", "Camera sample failed", it) }
            }
            "testCamera" -> camera.take { result ->
                result.onSuccess {
                    File(cacheDir, "camera-test.jpg").writeBytes(it)
                    Log.i("MemoryCamera", "Test photo captured: ${it.size} bytes")
                }.onFailure { Log.e("MemoryCamera", "Test photo failed", it) }
              }
            "saveEmpty" -> savePhoto("")
            "newSession" -> newSession()
        }
    }

    override fun onResume() {
        super.onResume()
        try { server.start() } catch (e: Exception) { setStatus("SYNC: ${e.message}") }
        if (hasPermissions()) voice.start()
        if (syncPage && !directSync.active && wifi.isWifiEnabled &&
            syncPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) directSync.start()
        showMenu()
    }

    override fun onPause() {
        hud.removeCallbacks(hideMenu)
        voice.stop()
        super.onPause()
    }

    override fun onDestroy() {
        voice.close()
        directSync.stop()
        server.stop()
        camera.close()
        super.onDestroy()
    }

    private fun ensurePermissions() {
        if (!hasPermissions()) requestPermissions(arrayOf(Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun hasPermissions() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (syncPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) directSync.start()
            else setStatus("ALLOW NEARBY DEVICES AND LOCATION")
            return
        }
        if (hasPermissions()) voice.start() else setStatus("CAMERA AND MIC NEEDED")
    }

    private fun handleCommand(command: String) {
        when (command) {
            "сделать фото", "сделай фото", "сфотографировать",
            "take photo", "take a photo", "capture photo" -> takePhoto()
        }
    }

    private fun takePhoto() {
        if (store.current() == null) { setStatus("SWIPE FORWARD: NEW SESSION"); return }
        if (!hasPermissions()) { ensurePermissions(); return }
        if (pendingPhoto != null) { setStatus("SAY NOTE + SEND, OR TAP"); return }
        if (capturing) return
        directSync.stop()
        capturing = true
        syncPage = false
        showMenu(false)
        voice.mute(true)
        setStatus("CAPTURING...")
        voice.suspendForCamera { released -> runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (!released) {
                capturing = false
                finishRequested = false; exitAfterFinish = false
                voice.mute(false)
                voice.resumeAfterCamera()
                setStatus("PHOTO: SPEECH BUSY. PLEASE RETRY")
                return@runOnUiThread
            }
            camera.take { result -> runOnUiThread {
            capturing = false
            result.fold({ bytes ->
                pendingPhoto = bytes
                if (!finishRequested) voice.expectNote()
                voice.mute(false)
                setStatus("LOADING SPEECH. PLEASE WAIT")
                voice.resumeAfterCamera()
                if (finishRequested) savePhoto("")
            }, { error ->
                finishRequested = false; exitAfterFinish = false
                voice.mute(false)
                voice.resumeAfterCamera()
                setStatus("PHOTO: ${error.message ?: "ERROR"}")
            })
            } }
        } }
    }

    private fun savePhoto(note: String) {
        val bytes = pendingPhoto ?: return
        try {
            val step = store.addStep(bytes, note)
            pendingPhoto = null
            voice.cancelNote()
            voice.mute(false)
            setStatus("STEP ${step.getInt("number")} SAVED")
            if (finishRequested) completeSession() else showMenu()
        } catch (e: Exception) {
            finishRequested = false; exitAfterFinish = false
            setStatus("SAVE: ${e.message}")
        }
    }

    private fun newSession() {
        if (capturing) return
        if (pendingPhoto != null) { setStatus("SAVE THE PHOTO FIRST"); return }
        directSync.stop()
        if (store.current() != null) {
            syncPage = false
            setStatus("CONTINUING. DOUBLE TAP: FINISH")
            showMenu()
            return
        }
        store.newSession()
        syncPage = false
        setStatus("NEW DISASSEMBLY")
        showMenu()
    }

    private fun finishSession() {
        if (syncPage) { directSync.stop(); syncPage = false; showMenu(); return }
        Log.i("MemoryInput", "finish requested capturing=$capturing pending=${pendingPhoto != null} queued=$finishRequested active=${store.current() != null}")
        if (finishRequested) {
            exitAfterFinish = true
            setStatus("FINISHING, THEN EXIT")
            return
        }
        if (store.current() == null) { exitToHome(); return }
        finishRequested = true
        showMenu(false)
        if (capturing) { setStatus("FINISHING AFTER PHOTO"); return }
        if (pendingPhoto != null) {
            setStatus("FINISHING: SAVING PHOTO + NOTE")
            if (!voice.submitNote()) savePhoto(voice.currentDraft())
            return
        }
        completeSession()
    }

    private fun completeSession() {
        try {
            store.finishSession()
            finishRequested = false
            syncPage = false
            setStatus("FINISHED. DOUBLE TAP: EXIT")
            showMenu(false)
            Log.i("MemoryInput", "Session finished; exit=$exitAfterFinish")
            if (exitAfterFinish) exitToHome()
        } catch (e: Exception) {
            finishRequested = false; exitAfterFinish = false
            setStatus("SAVE: ${e.message}")
        }
    }

    private fun exitToHome() {
        Log.i("MemoryInput", "Exit to glasses home")
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        catch (e: Exception) { Log.w("MemoryInput", "Home screen unavailable", e) }
        finish()
    }

    private fun showMenu(autoHide: Boolean = true) {
        quietMode = false
        hud.removeCallbacks(hideMenu)
        if (autoHide && !syncPage && !capturing && pendingPhoto == null && store.current() != null) {
            hud.postDelayed(hideMenu, 5000)
        }
        hud.invalidate()
    }

    private fun setStatus(value: String) {
        status = value
        if (value.startsWith("PHOTO:") || value.startsWith("SAVE:") || value.startsWith("SPEECH:"))
            showMenu(false)
        hud.invalidate()
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            setStatus("OPEN WI-FI IN GLASSES SETTINGS")
        }
    }

    private fun syncPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    private fun openDirectSync() {
        if (pendingPhoto != null || capturing) { setStatus("SAVE THE PHOTO FIRST"); return }
        syncPage = true
        showMenu(false)
        if (!wifi.isWifiEnabled) { openWifiSettings(); return }
        val permissions = syncPermissions()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, 2)
        } else directSync.start()
    }

    /** Rokid sends vendor swipe scan codes 183/184 instead of DPAD on some firmware. */
    private fun swipeDirection(event: KeyEvent): Int {
        val rokid = event.device?.name?.startsWith("ROKID,PSOC-TP") == true
        if (rokid) {
            when (event.scanCode) {
                183 -> return 1
                184 -> return -1
            }
            val forward = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_SWIPE_FORWARD")
            val back = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_SWIPE_BACK")
            if (forward != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == forward) return 1
            if (back != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == back) return -1
            // Physical forward: RIGHT then DOWN. Physical back: LEFT (sometimes twice) then UP.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> -1
            else -> 0
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val rokid = event.device?.name?.startsWith("ROKID,PSOC-TP") == true
        val spriteDoubleTap = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_DOUBLE_TAP")
        if (rokid && (event.keyCode == KeyEvent.KEYCODE_BACK || event.scanCode == 202 ||
                    (spriteDoubleTap != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == spriteDoubleTap))) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastDoubleTapAt > 350) {
                    lastDoubleTapAt = now
                    Log.d("MemoryInput", "doubleTap scan=${event.scanCode} key=${event.keyCode}")
                    finishSession()
                }
            }
            return true
        }
        val direction = swipeDirection(event)
        if (direction != 0) {
            Log.d("MemoryInput", "key=${event.keyCode} scan=${event.scanCode} phase=${event.action} repeat=${event.repeatCount} device=${event.device?.name} direction=$direction")
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                val duplicateWindow = if (event.device?.name?.startsWith("ROKID,PSOC-TP") == true) 700 else 250
                if (direction != lastSwipeDirection || now - lastSwipeAt > duplicateWindow) {
                    lastSwipeAt = now
                    lastSwipeDirection = direction
                    if (direction > 0) {
                        if (syncPage) { voice.switchLanguage(); hud.invalidate() }
                        else newSession()
                    } else { openDirectSync() }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) {
                    Log.d("MemoryInput", "tap scan=${event.scanCode} device=${event.device?.name} pending=${pendingPhoto != null}")
                    if (syncPage) {
                        if (!wifi.isWifiEnabled) openWifiSettings()
                        else if (!directSync.active) openDirectSync()
                        else { directSync.stop(); syncPage = false; showMenu() }
                    }
                    else if (pendingPhoto != null) {
                        if (voice.submitNote()) setStatus("SAVING PHOTO + NOTE")
                        else savePhoto(voice.currentDraft())
                    } else takePhoto()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (syncPage) { directSync.stop(); syncPage = false; showMenu(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.name == "wlan0" && it.isUp }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    private inner class Hud : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private fun line(canvas: Canvas, text: String, y: Float, size: Float = 22f) {
            paint.textSize = size
            val textWidth = paint.measureText(text)
            if (textWidth > 436f) paint.textSize = size * 436f / textWidth
            canvas.drawText(text, 22f, y, paint)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val sx = width / 480f; val sy = height / 640f
            canvas.save(); canvas.scale(sx, sy)
            if (quietMode && !syncPage && !capturing && pendingPhoto == null) {
                line(canvas, "WAITING / ОЖИДАНИЕ", 565f, 16f)
                line(canvas, "TAP / СДЕЛАТЬ ФОТО / TAKE PHOTO", 590f, 16f)
                line(canvas, "SWIPE BACK: SYNC / СИНХРОНИЗАЦИЯ", 615f, 16f)
                canvas.restore()
                return
            }
            line(canvas, "RESTEP", 44f, 30f)
            paint.strokeWidth = 2f
            canvas.drawLine(20f, 58f, 460f, 58f, paint)
            val current = store.current()
            val count = current?.getJSONArray("steps")?.length() ?: 0
            line(canvas, if (current == null) "DISASSEMBLY FINISHED" else "DISASSEMBLY IN PROGRESS", 106f, 20f)
            line(canvas, "PHOTOS: $count", 143f)
            if (syncPage) {
                line(canvas, "SYNC WITH IPHONE", 211f, 26f)
                line(canvas, directSync.state, 257f, 19f)
                line(canvas, directSync.ssid, 298f, 18f)
                line(canvas, "IPHONE: RESTEP > CONNECT & SYNC", 342f, 17f)
                line(canvas, "ALLOW BLUETOOTH PAIRING", 378f, 17f)
                line(canvas, if (directSync.active) "TAP / DOUBLE TAP: CLOSE SYNC" else "TAP: RETRY / WI-FI SETTINGS", 417f, 17f)
                line(canvas, "TAP NOTE: ${voice.language.uppercase()}  FORWARD: SWITCH", 464f, 17f)
                line(canvas, "MANUAL: ${localIp() ?: "NO LAN"}  $pairCode", 508f, 15f)
                postInvalidateDelayed(1500)
            } else {
                when {
                    capturing -> {
                        line(canvas, "TAKING PHOTO", 218f, 22f)
                        line(canvas, "HOLD STILL...", 253f, 19f)
                    }
                    current == null -> {
                        line(canvas, "DISASSEMBLY FINISHED", 180f, 18f)
                        line(canvas, "FORWARD: NEW DISASSEMBLY", 218f, 20f)
                    }
                    pendingPhoto == null -> {
                        line(canvas, "WAITING", 180f, 18f)
                        line(canvas, "СДЕЛАТЬ ФОТО / TAKE PHOTO", 218f, 19f)
                        line(canvas, "SAY A DESCRIPTION AFTER THE PHOTO", 253f, 17f)
                        line(canvas, "THEN: ОТПРАВИТЬ / SEND", 287f, 18f)
                    }
                    else -> {
                        line(canvas, "PHOTO READY", 180f, 18f)
                        line(canvas, if (status.startsWith("LOADING")) "PREPARING MICROPHONE..."
                            else "SAY THE STEP DESCRIPTION", 218f, 20f)
                        line(canvas, "ОТПРАВИТЬ / SEND / SEND NOTE", 253f, 19f)
                        line(canvas, "NOTE LANGUAGE: ${voice.language.uppercase()}", 287f, 17f)
                    }
                }
                if (current != null) line(canvas, if (pendingPhoto != null)
                    "TAP: SAVE PHOTO + NOTE" else "TAP: TAKE PHOTO", 330f, 19f)
                line(canvas, "FORWARD: CONTINUE / NEW", 367f, 18f)
                line(canvas, "SWIPE BACK: OPEN SYNC", 404f, 19f)
                line(canvas, if (current == null) "DOUBLE TAP: EXIT" else "DOUBLE TAP: FINISH", 439f, 18f)
                val spokenNote = if (pendingPhoto != null) voice.currentDraft() else ""
                if (spokenNote.isNotBlank()) {
                    line(canvas, "NEW NOTE:", 468f, 17f)
                    line(canvas, spokenNote.take(42), 497f, 18f)
                    if (spokenNote.length > 42) line(canvas, spokenNote.drop(42).take(42), 525f, 18f)
                } else if (count > 0) {
                    val last = current!!.getJSONArray("steps").getJSONObject(count - 1).optString("note")
                    line(canvas, "LAST NOTE:", 468f, 17f)
                    line(canvas, (last.ifBlank { "NO DESCRIPTION" }).take(42), 497f, 18f)
                    if (last.length > 42) line(canvas, last.drop(42).take(42), 525f, 18f)
                }
                if (pendingPhoto != null) postInvalidateDelayed(250)
            }
            canvas.drawLine(20f, 562f, 460f, 562f, paint)
            if (syncPage) {
                line(canvas, status.take(42), 599f, 17f)
            } else {
                val visibleStatus = when {
                    finishRequested -> "FINISHING - SAVING CURRENT STEP"
                    capturing -> "TAKING PHOTO - HOLD STILL"
                    status.startsWith("STEP ") -> status
                    status.startsWith("SPEECH:") || status.startsWith("PHOTO:") || status.startsWith("SAVE:") -> status
                    pendingPhoto != null && status.startsWith("NOTE READY") -> "NOTE READY"
                    pendingPhoto != null && status.startsWith("LOADING") -> "LOADING SPEECH - PLEASE WAIT"
                    pendingPhoto != null -> "PHOTO READY - SAY DESCRIPTION"
                    current != null -> "WAITING"
                    else -> "DISASSEMBLY FINISHED"
                }
                line(canvas, visibleStatus.take(42), 585f, 16f)
                val hint = when {
                    capturing -> "PLEASE WAIT"
                    pendingPhoto != null -> "TAP TO SAVE / ОТПРАВИТЬ / SEND"
                    current != null -> "TAP / СДЕЛАТЬ ФОТО / TAKE PHOTO"
                    else -> "FORWARD: NEW DISASSEMBLY"
                }
                line(canvas, hint, 610f, 16f)
            }
            canvas.restore()
        }
    }
}
