package com.rocketglasses.restep.phone

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@SuppressLint("MissingPermission")
class GlassesLink(private val context: Context, private val status: (String)->Unit,
                  private val ready: (Network, JSONObject)->Unit) {
    private val service = UUID.fromString("712e3100-92e5-4c52-8d0f-332baeef6001")
    private val info = UUID.fromString("712e3101-92e5-4c52-8d0f-332baeef6001")
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var active = false
    private var found = false
    private val timeout = Runnable { if(active) { stop(); status("Connection timed out. Open Sync on the glasses and try again.") } }
    private val scan = object: ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if(!active || found) return@post
            found = true; adapter.bluetoothLeScanner.stopScan(this)
            status("Pair with your glasses when Android asks…")
            gatt = result.device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
        } }
        override fun onScanFailed(errorCode: Int) { handler.post { stop(); status("Bluetooth scan failed ($errorCode). Try again.") } }
    }
    private val callbacks = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if(state == BluetoothProfile.STATE_CONNECTED && code == 0) g.discoverServices()
            else if(state == BluetoothProfile.STATE_DISCONNECTED) handler.post { if(active && networkCallback == null) { stop(); status("Glasses disconnected. Please retry.") } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            val ch = g.getService(service)?.getCharacteristic(info)
            if(code == 0 && ch != null) g.readCharacteristic(ch)
            else handler.post { stop(); status("ReStep sync service was not found.") }
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, code: Int) {
            if(Build.VERSION.SDK_INT < 33) received(g, ch.value ?: byteArrayOf(), code)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, code: Int) { received(g, value, code) }
    }
    private fun received(g: BluetoothGatt, bytes: ByteArray, code: Int) { handler.post {
        if(!active || g !== gatt) return@post
        if(code != BluetoothGatt.GATT_SUCCESS) { stop(); status("Pairing was not completed. Pair in Bluetooth settings, then retry."); return@post }
        try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            validate(value)
            status("Approve the ReStep Wi-Fi connection…")
            val spec = WifiNetworkSpecifier.Builder().setSsid(value.getString("ssid")).setWpa2Passphrase(value.getString("password")).build()
            val callback = object: ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { if(active) { handler.removeCallbacks(timeout); ready(network, value) } } }
                override fun onUnavailable() { handler.post { stop(); status("Wi-Fi connection declined or unavailable. Please retry.") } }
                override fun onLost(network: Network) { handler.post { if(active) { stop(); status("Glasses Wi-Fi disconnected.") } } }
            }
            networkCallback = callback
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build(), callback)
        } catch(e: Exception) { stop(); status(e.message ?: "Cannot connect") }
    } }
    fun start() {
        stop()
        check(adapter != null && adapter.isEnabled) { "Enable Bluetooth and Wi-Fi first." }
        active = true; found = false
        adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.postDelayed(timeout, 90000)
        status("Looking for ReStep… Swipe back on the glasses to open Sync.")
    }
    fun stop() {
        active = false; handler.removeCallbacks(timeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        gatt?.close(); gatt = null
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; networkCallback = null
    }
    companion object {
        fun validate(value: JSONObject) {
            require(value.getInt("version") == 1 && value.getString("ssid").startsWith("DIRECT-RS-ReStep-")) { "Invalid ReStep connection" }
            val parts = value.getString("host").split('.').map { it.toIntOrNull() ?: -1 }
            require(parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31)) { "Invalid local address" }
            require(value.getInt("port") == 8765)
            UUID.fromString(value.getString("token"))
        }
        fun request(network: Network?, host: String, token: String, path: String, method: String = "GET"): ByteArray {
            val url = URL("http://$host:8765$path")
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                connection.connectTimeout = 10000; connection.readTimeout = 20000
                connection.instanceFollowRedirects = false; connection.requestMethod = method
                connection.setRequestProperty("X-Pair-Code", token)
                val code = connection.responseCode
                check(code in 200..299) { when(code) { 403 -> "Pairing expired. Reconnect to the glasses."; 409 -> "Save the current photo on the glasses before deleting."; else -> "Glasses returned HTTP $code" } }
                return connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) { val n = input.read(buffer); if(n < 0) break; check(out.size() + n <= 32 * 1024 * 1024) { "Response too large" }; out.write(buffer,0,n) }
                    out.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    }
}
