package com.rocketglasses.soberyobratno

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/** A normal WPA2 network hosted by the glasses, bootstrapped over encrypted BLE. */
@SuppressLint("MissingPermission")
class DirectSync(private val context: Context, private val changed: () -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("712e3100-92e5-4c52-8d0f-332baeef6001")
        val INFO: UUID = UUID.fromString("712e3101-92e5-4c52-8d0f-332baeef6001")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("direct-sync", Context.MODE_PRIVATE)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var gatt: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var ownedGroup = false
    private var generation = 0
    private val snapshots = mutableMapOf<String, ByteArray>()
    @Volatile var active = false; private set
    @Volatile var token: String? = null; private set
    var state = "OFF"; private set
    var ssid = prefs.getString("ssid", null) ?: "DIRECT-RS-ReStep-${UUID.randomUUID().toString().take(4)}"
        private set
    private val password = prefs.getString("password", null) ?: UUID.randomUUID().toString().replace("-", "").take(20)
    private var host = ""

    init { prefs.edit().putString("ssid", ssid).putString("password", password).apply() }

    private fun update(value: String) { state = value; Log.i("ReStepSync", value); changed() }

    fun start() {
        if (active) return
        active = true
        val run = ++generation
        token = UUID.randomUUID().toString()
        host = ""
        update("STARTING WI-FI")
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null || !adapter.isEnabled) { fail("ENABLE BLUETOOTH"); return }
            advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) { fail("BLUETOOTH SHARING UNAVAILABLE"); return }
            channel = channel ?: manager.initialize(context, Looper.getMainLooper()) {
                channel = null
                if (active) fail("WI-FI CONNECTION LOST. RETRY")
            }
            if (!wifi.isWifiEnabled) { fail("ENABLE WI-FI, THEN RETRY"); return }
            manager.requestGroupInfo(channel) { existing ->
                if (run != generation || !active) return@requestGroupInfo
                if (android.os.Build.VERSION.SDK_INT < 29) { fail("DIRECT SYNC NEEDS ANDROID 10"); return@requestGroupInfo }
                if (existing != null) {
                    // The OS may retain our group after an APK update or process death.
                    if (existing.isGroupOwner && existing.networkName == ssid) {
                        ownedGroup = true
                        waitForAddress(run, 0)
                    } else fail("CLOSE HI ROKID TRANSFER, RETRY")
                    return@requestGroupInfo
                }
                val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(password)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build()
                manager.createGroup(channel!!, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (run != generation || !active) {
                            manager.removeGroup(channel, null)
                            return
                        }
                        ownedGroup = true
                        waitForAddress(run, 0)
                    }
                    override fun onFailure(reason: Int) {
                        if (run == generation && active) fail("WI-FI START FAILED ($reason)")
                    }
                })
            }
        } catch (e: Exception) { fail("SYNC: ${e.message?.take(65) ?: "UNAVAILABLE"}") }
    }

    private fun waitForAddress(run: Int, attempt: Int) {
        if (!active || run != generation) return
        manager.requestConnectionInfo(channel) { info ->
            if (!active || run != generation) return@requestConnectionInfo
            if (info.groupFormed && info.isGroupOwner && info.groupOwnerAddress != null) {
                host = info.groupOwnerAddress.hostAddress ?: ""
                startBluetooth()
            } else if (attempt < 20) handler.postDelayed({ waitForAddress(run, attempt + 1) }, 500)
            else fail("WI-FI TIMEOUT. RETRY")
        }
    }

    private fun startBluetooth() {
        try {
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(INFO,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            gatt = context.getSystemService(BluetoothManager::class.java).openGattServer(context,
                object : BluetoothGattServerCallback() {
                    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                        handler.post {
                            if (!active || gatt == null) return@post
                            if (status != BluetoothGatt.GATT_SUCCESS) { fail("BLUETOOTH SERVICE FAILED"); return@post }
                            advertiser?.startAdvertising(AdvertiseSettings.Builder()
                                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                                .setConnectable(true).setTimeout(0).build(),
                                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertising)
                        }
                    }
                    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_DISCONNECTED) synchronized(snapshots) { snapshots.remove(device.address) }
                    }
                    override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
                        offset: Int, characteristic: BluetoothGattCharacteristic) {
                        if (!active || characteristic.uuid != INFO || device.bondState != BluetoothDevice.BOND_BONDED) {
                            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, offset, null)
                            return
                        }
                        val bytes = synchronized(snapshots) {
                            if (offset == 0) snapshots[device.address] = JSONObject()
                                .put("version", 1).put("ssid", ssid).put("password", password)
                                .put("host", host).put("port", 8765).put("token", token).toString().toByteArray()
                            snapshots[device.address]
                        }
                        if (bytes == null || offset > bytes.size) {
                            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                        } else gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
                    }
                })
            if (gatt?.addService(service) != true) fail("BLUETOOTH SERVICE UNAVAILABLE")
        } catch (e: Exception) { fail("BLUETOOTH: ${e.message?.take(60)}") }
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { handler.post { if (active) update("READY FOR IPHONE") } }
        override fun onStartFailure(errorCode: Int) { handler.post { if (active) fail("BLUETOOTH START FAILED ($errorCode)") } }
    }

    private fun fail(message: String) {
        stop()
        update(message)
    }

    fun stop() {
        active = false
        generation++
        token = null
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        advertiser = null
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        synchronized(snapshots) { snapshots.clear() }
        if (ownedGroup) {
            try { manager.removeGroup(channel, null) } catch (_: Exception) {}
            ownedGroup = false
        }
        update("OFF")
    }
}
