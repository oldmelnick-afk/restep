package com.rocketglasses.restep.phone

import android.Manifest
import android.app.*
import android.os.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Network
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity: Activity() {
    companion object {
        private var sharedLibrary: Library? = null
        private var syncing = false
        private var visible: java.lang.ref.WeakReference<MainActivity>? = null
    }
    private lateinit var library: Library
    private lateinit var link: GlassesLink
    private lateinit var body: LinearLayout
    private lateinit var message: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val busy get() = syncing
    private var selected: String? = null
    private var assembly = true
    private val ink = Color.rgb(23,35,39)
    private val accent = Color.rgb(0,125,115)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        library = sharedLibrary ?: Library(filesDir).also { sharedLibrary = it }
        link = GlassesLink(this, { text -> message.text = text }, { network, info -> sync(network, info.getString("host"),info.getString("token")) })
        render()
    }
    override fun onResume() { super.onResume(); visible = java.lang.ref.WeakReference(this); render() }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(0,12,0,12) }
    private fun button(text: String, action: ()->Unit) = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(accent); isEnabled = !busy; setOnClickListener { action() } }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(246,248,247)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,32) }
        scroll.addView(body)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets
        }
        setContentView(scroll)
        body.addView(label("ReStep",34f))
        message = label(if(busy) "Synchronizing…" else "Record the way back.")
        body.addView(message)
        val session = library.sessions().firstOrNull { it.getString("id") == selected }
        if(session == null) showLibrary() else showSession(session)
    }
    private fun showLibrary() {
        body.addView(button("Connect & sync") { connect() })
        body.addView(label("On your glasses, open ReStep and swipe back to Sync. Keep this screen open while transferring."))
        body.addView(button("Manual connection") { manual() })
        if(library.pending().isNotEmpty()) body.addView(label("${library.pending().size} deletion(s) waiting for glasses sync."))
        body.addView(label("Your projects",24f))
        if(library.sessions().isEmpty()) body.addView(label("Your disassembly sessions will appear here after syncing."))
        for(session in library.sessions()) {
            body.addView(button(session.getString("name")) { selected = session.getString("id"); render() })
            body.addView(label("${session.getJSONArray("steps").length()} steps · ${if(session.has("endedAt")) "Finished" else "In progress"}"))
        }
    }
    private fun showSession(session: JSONObject) {
        val id = session.getString("id")
        body.addView(button("‹ Projects") { selected = null; render() })
        body.addView(label(session.getString("name"),26f))
        body.addView(button("Rename project") { editText("Project name", session.getString("name")) { text -> library.edit(id) { it.put("name",text) }; render() } })
        body.addView(button(if(assembly) "Assembly ↓  ·  switch to Disassembly" else "Disassembly ↑  ·  switch to Assembly") { assembly = !assembly; render() })
        val steps = session.getJSONArray("steps").objects().let { if(assembly) it.reversed() else it }
        for((index,step) in steps.withIndex()) {
            body.addView(label("${index + 1} / ${steps.size}  ·  Recorded step ${step.getInt("number")}",20f))
            val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Step photo" }
            body.addView(image, LinearLayout.LayoutParams(-1,-2))
            val photo = library.photo(step.getString("photo"))
            if(photo.exists()) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                image.setImageBitmap(BitmapFactory.decodeFile(photo.path,options))
            }
            body.addView(label(step.optString("note").ifBlank { "No description" },18f))
            body.addView(button("Edit description") { editText("Description",step.optString("note")) { text ->
                library.edit(id) { s -> s.getJSONArray("steps").objects().first { it.getString("id") == step.getString("id") }.put("note",text) }; render()
            } })
            val check = CheckBox(this).apply { text = "Step completed"; isChecked = step.optBoolean("completed"); isEnabled = !busy }
            check.setOnCheckedChangeListener { _, done -> library.edit(id) { s -> s.getJSONArray("steps").objects().first { it.getString("id") == step.getString("id") }.put("completed",done) } }
            body.addView(check)
        }
        body.addView(button("Delete project") {
            AlertDialog.Builder(this).setTitle("Delete this project?").setMessage("Photos and notes will be removed from this phone now and from your glasses at the next sync.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ ->
                    runCatching { library.delete(id); selected = null; render() }.onFailure { message.text = it.message }
                }.show()
        })
    }
    private fun editText(title: String, value: String, save: (String)->Unit) {
        val input = EditText(this).apply { setText(value); minLines = 2 }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
            runCatching { save(input.text.toString()) }.onFailure { message.text = it.message }
        }.show()
    }
    private fun permissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if(Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()
    private fun connect() {
        val missing = permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) { requestPermissions(permissions(),42); return }
        runCatching { link.start() }.onFailure { message.text = it.message }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if(requestCode == 42) {
            if(grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }) connect()
            else message.text = "Nearby devices and location permissions are needed to connect. You can also use Manual connection."
        }
    }
    private fun manual() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        box.addView(label("Connect both devices to the same Wi-Fi. Enter the address and pair code shown on the glasses."))
        val fields = (0..3).map { EditText(this).apply { hint = "0"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(3)) } }
        val row = LinearLayout(this)
        fields.forEachIndexed { index, field -> row.addView(field,LinearLayout.LayoutParams(0,-2,1f)); if(index < 3) row.addView(label(".")) }
        box.addView(row)
        val code = EditText(this).apply { hint = "Pair code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        box.addView(code)
        val dialog = AlertDialog.Builder(this).setTitle("Manual sync").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Sync",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parts = fields.map { it.text.toString().toIntOrNull() ?: -1 }
            if(parts.any { it !in 0..255 } || !code.text.matches(Regex("[0-9]{6}"))) { code.error = "Enter four IP numbers (0–255) and the six-digit code" }
            else { dialog.dismiss(); link.stop(); sync(null,parts.joinToString("."),code.text.toString()) }
        } }; dialog.show()
    }
    private fun sync(network: Network?, host: String, token: String) {
        if(busy) return
        syncing = true; render()
        worker.execute {
            val result = runCatching {
                for(id in library.pending()) { GlassesLink.request(network,host,token,"/session/$id","DELETE"); library.acknowledge(id) }
                val remote = JSONObject(GlassesLink.request(network,host,token,"/manifest").toString(Charsets.UTF_8))
                require(remote.getInt("schemaVersion") == 1)
                for(session in remote.getJSONArray("sessions").objects()) {
                    if(session.getString("id") in library.deleted()) continue
                    for(step in session.getJSONArray("steps").objects()) {
                        val path = step.getString("photo")
                        if(!library.photo(path).exists()) library.storePhoto(path,GlassesLink.request(network,host,token,"/photo/$path"))
                    }
                }
                library.merge(remote)
            }
            runOnUiThread {
                link.stop(); syncing = false
                visible?.get()?.takeIf { !it.isDestroyed }?.let { screen ->
                    screen.render(); screen.message.text = result.fold({ "Sync complete. ${library.sessions().size} project(s) saved offline." },{ it.message ?: "Sync failed. Please retry." })
                }
            }
        }
    }
    override fun onDestroy() { link.stop(); worker.shutdown(); super.onDestroy() }
}
