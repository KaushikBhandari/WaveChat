package com.example.wavechat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.app.ActivityCompat
import java.util.Collections
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val TAG = "WaveChat"

    // ── App screens ────────────────────────────────────────────────
    enum class Screen { SETUP, SCAN, CHAT }
    private var screen by mutableStateOf(Screen.SCAN)
    
    enum class ScanMode { BLE, WIFI }
    private var currentScanMode by mutableStateOf(ScanMode.BLE)

    // ── State ──────────────────────────────────────────────────────
    private val discoveredBleDevices  = mutableStateListOf<BleDevice>()
    data class WifiPeer(val device: WifiP2pDevice, val customName: String)
    private val discoveredWifiDevices = mutableStateListOf<WifiPeer>()
    
    private val selectedBlePeers = mutableStateListOf<String>()
    private var activeWifiPeerAddress by mutableStateOf<String?>(null)
    private var activeWifiPeerName by mutableStateOf<String>("")
    
    private val knownPeers = mutableStateMapOf<String, String>()
    private var activeRecipientId by mutableStateOf(MeshMessage.BROADCAST_ID)
    
    // active connection states
    private var isConnected by mutableStateOf(false)
    private val connectedGatts = mutableMapOf<String, BluetoothGatt>()

    // Single chat history
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private val peerPublicKeys = mutableMapOf<String, String>()

    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val senderLabel: String = "",
        val isRelayed: Boolean = false,
        val hopCount: Int = 0
    )

    private var myAddress     by mutableStateOf("local")
    private var myName        by mutableStateOf("")
    private var nameInput     by mutableStateOf("")
    private var status        by mutableStateOf("Starting…")
    private var statusOk      by mutableStateOf(false)
    private var messageText   by mutableStateOf("")
    private var pendingCount  by mutableStateOf(0)
    private var debugLog      by mutableStateOf("")
    private var showPermDialog by mutableStateOf(false)
    private var permDialogMsg  by mutableStateOf("")
    
    private var showFallbackDialog by mutableStateOf(false)

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID    = UUID.fromString("abcdef01-1234-1234-1234-abcdefabcdef")

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter get() = bluetoothManager.adapter
    private lateinit var meshRouter: MeshRouter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null
    
    private lateinit var wifiDirectManager: WifiDirectManager

    private fun dbg(msg: String) { Log.d(TAG, msg); runOnUiThread { debugLog = msg } }
    private fun setStatus(msg: String, ok: Boolean) { status = msg; statusOk = ok }

    // ── BT state receiver ──────────────────────────────────────────
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> runOnUiThread {
                    setStatus("Bluetooth ON — scanning…", true); startBle()
                }
                BluetoothAdapter.STATE_OFF -> runOnUiThread {
                    setStatus("Bluetooth is OFF — please turn it on", false)
                    discoveredBleDevices.clear()
                    seenAddresses.clear()
                    bleStarted = false
                    try { gattServer?.close() } catch (_: Exception) {}
                    gattServer = null
                }
                BluetoothAdapter.STATE_TURNING_ON ->
                    runOnUiThread { setStatus("Turning Bluetooth on…", false) }
            }
        }
    }

    // ── Launchers ──────────────────────────────────────────────────
    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) { setStatus("Bluetooth ON — scanning…", true); startBle() }
        else setStatus("Bluetooth is OFF — please enable it", false)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            checkBtAndStart()
        } else {
            val permanentlyDenied = denied.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }
            permDialogMsg = if (permanentlyDenied)
                "Some permissions were permanently denied. Please go to Settings → Apps → WaveChat → Permissions and enable requested permissions."
            else
                "WaveChat needs Bluetooth, Wi-Fi, and Location permissions to find nearby devices. Please allow all permissions."
            showPermDialog = true
            if (!permanentlyDenied)
                mainHandler.postDelayed({ checkAndRequestPermissions() }, 500)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CryptoUtils.initKeys(this)
        
        wifiDirectManager = WifiDirectManager(this,
            onPeersChanged = { peers ->
                runOnUiThread {
                    peers.forEach { peer ->
                        val existing = discoveredWifiDevices.indexOfFirst { it.device.deviceAddress == peer.deviceAddress }
                        if (existing >= 0) {
                            discoveredWifiDevices[existing] = discoveredWifiDevices[existing].copy(device = peer)
                        } else {
                            discoveredWifiDevices.add(WifiPeer(peer, peer.deviceName ?: "Unknown"))
                        }
                    }
                    discoveredWifiDevices.removeAll { existingPeer ->
                        peers.none { it.deviceAddress == existingPeer.device.deviceAddress }
                    }
                }
            },
            onServiceFound = { device, customName ->
                runOnUiThread {
                    val existing = discoveredWifiDevices.indexOfFirst { it.device.deviceAddress == device.deviceAddress }
                    if (existing >= 0) {
                        discoveredWifiDevices[existing] = WifiPeer(device, customName)
                    } else {
                        discoveredWifiDevices.add(WifiPeer(device, customName))
                    }
                }
            },
            onConnectionInfoAvailable = { isGroupOwner, groupOwnerAddress ->
                runOnUiThread {
                    isConnected = true
                    setStatus("Connected via Wi-Fi!", true)
                }
            },
            onMessageReceived = { msg ->
                runOnUiThread {
                    if (msg.startsWith("NAME_HANDSHAKE:")) {
                        val parts = msg.substringAfter("NAME_HANDSHAKE:").split("|PUBKEY:")
                        activeWifiPeerName = parts[0]
                        if (parts.size > 1) {
                            activeWifiPeerAddress?.let { addr -> peerPublicKeys[addr] = parts[1] }
                        }
                        setStatus("Connected with $activeWifiPeerName", true)
                    } else {
                        val isEncrypted = msg.startsWith("ENC:")
                        val decryptedText = if (isEncrypted) {
                            try {
                                CryptoUtils.decrypt(msg.substringAfter("ENC:"))
                            } catch (e: Exception) {
                                "[Encrypted message - failed to decrypt]"
                            }
                        } else {
                            msg
                        }
                        // Only add to chat messages if it's not a PUBKEY message
                        if (!msg.startsWith("PUBKEY:")) {
                            chatMessages.add(ChatMessage(decryptedText, false, activeWifiPeerName))
                            setStatus("Message received", true)
                        }
                    }
                }
            },
            onDisconnected = {
                runOnUiThread {
                    isConnected = false
                    if (screen == Screen.CHAT && currentScanMode == ScanMode.WIFI) {
                        setStatus("Wi-Fi disconnected", false)
                        goBackToScan()
                    }
                }
            }
        )
        wifiDirectManager.init()

        meshRouter = MeshRouter("local") { msg, addr -> sendViaBle(msg, addr) }
        val prefs = getSharedPreferences("wavechat", Context.MODE_PRIVATE)
        val saved = prefs.getString("myName", "") ?: ""
        if (saved.isNotBlank()) {
            myName = saved
            try { wifiDirectManager.setDeviceName(myName) } catch (e: Exception) {}
            screen = Screen.SCAN
        } else {
            screen = Screen.SETUP
        }
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        setContent { MaterialTheme { AppUI() } }
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (showPermDialog && neededPermissions().isEmpty()) {
            showPermDialog = false; checkBtAndStart()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleStarted = false
        wifiDirectManager.cleanUp()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        try { scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) {}
        try { gattServer?.close(); gattServer = null } catch (_: Exception) {}
        try { connectedGatts.values.forEach { it.close() } } catch (_: Exception) {}
    }

    // ── Permissions ────────────────────────────────────────────────
    private fun has(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.filter { !has(it) }
    }

    private fun checkAndRequestPermissions() {
        val missing = neededPermissions()
        if (missing.isEmpty()) checkBtAndStart()
        else { setStatus("Requesting permissions…", false); permLauncher.launch(missing.toTypedArray()) }
    }

    private fun checkBtAndStart() {
        val adapter = bluetoothAdapter
        if (adapter == null) { setStatus("Bluetooth not supported", false); return }
        myAddress = try {
            if (has(Manifest.permission.BLUETOOTH_CONNECT)) adapter.address ?: "local" else "local"
        } catch (_: SecurityException) { "local" }
        meshRouter = MeshRouter(myAddress) { msg, addr -> sendViaBle(msg, addr) }
        if (myName.isNotBlank()) {
            try { adapter.name = myName } catch (_: Exception) {}
        }
        if (!adapter.isEnabled) {
            setStatus("Bluetooth is OFF", false)
            try {
                @Suppress("DEPRECATION")
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) { dbg("BT enable failed: ${e.message}") }
        } else { setStatus("Scanning for nearby devices…", true); startBle() }
    }

    private var bleStarted = false
    private fun startBle() {
        if (bleStarted) return
        bleStarted = true
        startGattServer()
        startAdvertising()
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun saveName(name: String) {
        myName = name
        getSharedPreferences("wavechat", Context.MODE_PRIVATE)
            .edit().putString("myName", name).apply()
        try { bluetoothAdapter?.name = name } catch (_: Exception) {}
        try { wifiDirectManager.setDeviceName(name) } catch (_: Exception) {}
        
        if (isConnected && currentScanMode == ScanMode.WIFI) {
            wifiDirectManager.sendMessage("NAME_HANDSHAKE:$name|PUBKEY:${CryptoUtils.getMyPublicKeyString()}")
        }
        
        if (bleStarted) {
            startAdvertising()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        advertiseCallback?.let { 
            try { adv.stopAdvertising(it) } catch (e: Exception) {} 
        }
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) { dbg("Advertising OK") }
            override fun onStartFailure(c: Int) { dbg("Advertising FAILED $c") }
        }
        val nameBytes = myName.take(13).toByteArray(Charsets.UTF_8)
        
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
            
        val scanRespBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            
        if (nameBytes.isNotEmpty()) {
            scanRespBuilder.addServiceData(ParcelUuid(SERVICE_UUID), nameBytes)
        }
        
        try {
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true).setTimeout(0).build(),
                advData,
                scanRespBuilder.build(),
                advertiseCallback
            )
        } catch (e: SecurityException) { dbg("Adv SE: ${e.message}") }
    }

    private val seenAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    private fun friendlyName(addr: String): String {
        val suffix = addr.replace(":", "").takeLast(4).uppercase()
        return "Device $suffix"
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        mainHandler.postDelayed({
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                mainHandler.postDelayed({ startBleScan() }, 2000); return@postDelayed
            }
            scanCallback?.let { try { scanner.stopScan(it) } catch (_: Exception) {} }

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val addr = result.device.address
                    if (addr == myAddress) return

                    val isWaveChat = result.scanRecord?.serviceUuids
                        ?.contains(ParcelUuid(SERVICE_UUID)) == true
                    if (!isWaveChat) return

                    val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                    val customName = serviceData?.let { String(it, Charsets.UTF_8) } 
                        ?: result.device.name?.takeIf { it.isNotBlank() } 
                        ?: friendlyName(addr)

                    val device = BleDevice(
                        name     = customName,
                        address  = addr,
                        rssi     = result.rssi,
                        lastSeen = System.currentTimeMillis()
                    )

                    runOnUiThread {
                        val existing = discoveredBleDevices.indexOfFirst { it.address == addr }
                        if (existing >= 0) {
                            discoveredBleDevices[existing] = device
                        } else {
                            if (seenAddresses.add(addr)) {
                                discoveredBleDevices.add(device)
                            }
                        }
                    }
                }
            }
            scanCallback = cb
            try {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()
                scanner.startScan(
                    listOf(filter),
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                        .build(),
                    cb
                )
                runOnUiThread { setStatus("Scanning BLE devices…", true) }
                
                // Fallback timeout logic
                mainHandler.postDelayed({
                    if (screen == Screen.SCAN && currentScanMode == ScanMode.BLE && discoveredBleDevices.isEmpty()) {
                        showFallbackDialog = true
                    }
                }, 8000)
                
            } catch (e: Exception) { dbg("Scan ex: ${e.message}") }
        }, 1000)
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) return
        try {
            gattServer = bluetoothManager.openGattServer(this,
                object : BluetoothGattServerCallback() {
                    override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice, requestId: Int,
                        characteristic: BluetoothGattCharacteristic,
                        preparedWrite: Boolean, responseNeeded: Boolean,
                        offset: Int, value: ByteArray
                    ) {
                        if (responseNeeded) try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        } catch (e: SecurityException) { }

                        if (!connectedGatts.containsKey(device.address)) {
                            val newDevice = BleDevice(friendlyName(device.address), device.address, 0, System.currentTimeMillis())
                            runOnUiThread { connectToBlePeer(newDevice) }
                        }

                        val connectedAddrs = connectedGatts.keys.toList()
                        meshRouter.onMessageReceived(value, device.address, connectedAddrs) { msg ->
                            runOnUiThread {
                                if (msg.text.startsWith("PUBKEY:")) {
                                    peerPublicKeys[msg.senderId] = msg.text.substringAfter("PUBKEY:")
                                    val peerName = msg.senderName.takeIf { it.isNotBlank() } ?: friendlyName(msg.senderId)
                                    knownPeers[msg.senderId] = peerName
                                    return@runOnUiThread
                                }

                                val decryptedText = if (msg.isEncrypted) {
                                    try {
                                        CryptoUtils.decrypt(msg.text)
                                    } catch (e: Exception) {
                                        "[Encrypted message - failed to decrypt]"
                                    }
                                } else {
                                    msg.text
                                }

                                val label = msg.senderName.takeIf { it.isNotBlank() }
                                    ?: friendlyName(device.address)
                                knownPeers[msg.senderId] = label
                                val chatMsg = ChatMessage(
                                    text        = decryptedText,
                                    isMe        = false,
                                    senderLabel = label,
                                    isRelayed   = msg.hopCount > 0,
                                    hopCount    = msg.hopCount
                                )
                                chatMessages.add(chatMsg)
                                setStatus("Message from $label", true)
                                pendingCount = meshRouter.pendingCount()
                            }
                        }
                    }
                }
            )
            val svc  = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val char = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            svc.addCharacteristic(char)
            gattServer?.addService(svc)
        } catch (e: SecurityException) { }
    }

    private val servicesReadySet = Collections.synchronizedSet(mutableSetOf<String>())
    private val sendQueue = mutableMapOf<String, MutableList<MeshMessage>>()

    @SuppressLint("MissingPermission")
    private fun connectToBlePeer(device: BleDevice) {
        if (connectedGatts.containsKey(device.address)) return
        setStatus("Connecting to ${device.name}…", true)
        
        try {
            val gatt = bluetoothAdapter!!.getRemoteDevice(device.address)
                .connectGatt(this, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedGatts[device.address] = g
                                runOnUiThread { isConnected = true }
                                mainHandler.postDelayed({
                                    try { g.requestMtu(512) } catch (e: SecurityException) {
                                        mainHandler.postDelayed({
                                            try { g.discoverServices() } catch (_: Exception) {}
                                        }, 300)
                                    }
                                }, 300)
                                meshRouter.onPeerConnected(device.address)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                servicesReadySet.remove(device.address)
                                connectedGatts.remove(device.address)
                                try { g.close() } catch (_: SecurityException) {}
                                runOnUiThread {
                                    isConnected = false
                                    if (screen == Screen.CHAT) setStatus("Disconnected", false)
                                }
                            }
                        }
                    }
                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        mainHandler.postDelayed({
                            try { g.discoverServices() } catch (e: SecurityException) { }
                        }, 300)
                    }
                    override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                        if (st != BluetoothGatt.GATT_SUCCESS) {
                            mainHandler.postDelayed({
                                try { g.discoverServices() } catch (_: Exception) {}
                            }, 1000)
                            return
                        }
                        val ourService = g.getService(SERVICE_UUID)
                        if (ourService == null) {
                            runOnUiThread { setStatus("Service not found", false) }
                            return
                        }
                        servicesReadySet.add(device.address)
                        runOnUiThread {
                            setStatus("Ready to chat!", true)
                            val pubKeyMsg = meshRouter.originateMessage(
                                "PUBKEY:${CryptoUtils.getMyPublicKeyString()}",
                                connectedGatts.keys.toList(),
                                recipientId = MeshMessage.BROADCAST_ID,
                                senderName = myName
                            )

                            sendQueue[device.address]?.forEach { msg -> sendViaBle(msg, device.address) }
                            sendQueue.remove(device.address)
                        }
                    }
                }, BluetoothDevice.TRANSPORT_LE)
            connectedGatts[device.address] = gatt
        } catch (e: SecurityException) { }
    }
    
    private fun connectToWifiPeer(device: WifiP2pDevice) {
        activeWifiPeerAddress = device.deviceAddress
        activeWifiPeerName = device.deviceName
        screen = Screen.CHAT
        chatMessages.clear()
        setStatus("Connecting to ${device.deviceName} via Wi-Fi…", true)
        wifiDirectManager.connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAll() {
        if (currentScanMode == ScanMode.BLE) {
            connectedGatts.values.forEach { 
                try { it.disconnect(); it.close() } catch (_: SecurityException) {}
            }
            connectedGatts.clear()
            servicesReadySet.clear()
        } else {
            wifiDirectManager.disconnect()
        }
        isConnected = false
        activeWifiPeerAddress = null
        activeWifiPeerName = ""
        selectedBlePeers.clear()
    }

    private fun goBackToScan() {
        screen = Screen.SCAN
        disconnectAll()
        chatMessages.clear()
        setStatus(if (currentScanMode == ScanMode.BLE) "Scanning BLE…" else "Scanning Wi-Fi…", true)
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBle(msg: MeshMessage, peerAddress: String) {
        val gatt = connectedGatts[peerAddress] ?: return
        if (!servicesReadySet.contains(peerAddress)) {
            sendQueue.getOrPut(peerAddress) { mutableListOf() }.add(msg)
            return
        }
        val svc  = gatt.getService(SERVICE_UUID) ?: return
        val char = svc.getCharacteristic(CHAR_UUID) ?: return
        val bytes = msg.toBytes()
        if (bytes.size > 512) return
        try {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            char.value = bytes
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) { }
    }

    private fun sendMessage() {
        if (messageText.isBlank()) return
        val text = messageText.trim()
        val chatMsg = ChatMessage(text, true, "Me")
        
        if (currentScanMode == ScanMode.BLE) {
            val targets = connectedGatts.keys.toList()
            if (targets.isEmpty()) return
            
            val recipient = activeRecipientId
            val pubKey = peerPublicKeys[recipient]
            
            val (finalText, isEnc) = if (pubKey != null && recipient != MeshMessage.BROADCAST_ID) {
                Pair(CryptoUtils.encrypt(text, pubKey), true)
            } else {
                Pair(text, false)
            }
            
            val msg = meshRouter.originateMessage(finalText, targets, recipientId = recipient, senderName = myName, isEncrypted = isEnc)
            chatMessages.add(chatMsg)
            pendingCount = meshRouter.pendingCount()
            messageText = ""
            setStatus("Message sent!", true)
        } else {
            activeWifiPeerAddress?.let { addr ->
                val pubKey = peerPublicKeys[addr]
                if (pubKey != null) {
                    wifiDirectManager.sendMessage("ENC:${CryptoUtils.encrypt(text, pubKey)}")
                } else {
                    wifiDirectManager.sendMessage(text)
                }
            } ?: run {
                wifiDirectManager.sendMessage(text)
            }
            chatMessages.add(chatMsg)
            messageText = ""
            setStatus("Message sent!", true)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════
    private val darkBg    = Color(0xFF0A0E1A)
    private val cardBg    = Color(0xFF131929)
    private val accent    = Color(0xFF00D4FF)
    private val accentSec = Color(0xFF7B61FF)
    private val success   = Color(0xFF00E676)
    private val danger    = Color(0xFFFF5252)
    private val textPri   = Color(0xFFEEF2FF)
    private val textSec   = Color(0xFF8892B0)

    @Composable
    private fun AppUI() {
        val pulse = rememberInfiniteTransition(label = "p")
        val pulseA by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")

        if (showPermDialog) {
            AlertDialog(
                onDismissRequest = { showPermDialog = false },
                containerColor = cardBg,
                title = { Text("Permissions Required", color = accent, fontWeight = FontWeight.Bold) },
                text  = { Text(permDialogMsg, color = textSec, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermDialog = false
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) { Text("Open Settings", color = Color.Black, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showPermDialog = false; checkAndRequestPermissions() }) {
                        Text("Try Again", color = accentSec)
                    }
                }
            )
        }
        
        if (showFallbackDialog) {
            AlertDialog(
                onDismissRequest = { showFallbackDialog = false },
                containerColor = cardBg,
                title = { Text("No Devices Found", color = accent, fontWeight = FontWeight.Bold) },
                text  = { Text("No Bluetooth devices found nearby. Search Wi-Fi devices instead?", color = textSec, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            showFallbackDialog = false
                            currentScanMode = ScanMode.WIFI
                            if (!wifiDirectManager.isWifiEnabled()) {
                                setStatus("Please turn on Wi-Fi in settings", false)
                                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            } else {
                                setStatus("Scanning for Wi-Fi devices…", true)
                                wifiDirectManager.discoverPeers()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) { Text("Yes, Search Wi-Fi", color = Color.Black, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showFallbackDialog = false }) {
                        Text("No, Keep using BLE", color = accentSec)
                    }
                }
            )
        }

        Box(Modifier.fillMaxSize().background(darkBg)) {
            when (screen) {
                Screen.SETUP -> SetupScreen()
                Screen.SCAN  -> ScanScreen(pulseA)
                Screen.CHAT  -> ChatScreen(pulseA)
            }
        }
    }

    @Composable
    private fun SetupScreen() {
        val isEdit = myName.isNotBlank()
        androidx.activity.compose.BackHandler(enabled = isEdit) {
            nameInput = ""
            screen = Screen.SCAN
        }
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(Brush.linearGradient(listOf(accent, accentSec)), CircleShape), contentAlignment = Alignment.Center) { Text("📡", fontSize = 34.sp) }
            Spacer(Modifier.height(24.dp))
            Text("WaveChat", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textPri)
            Spacer(Modifier.height(6.dp))
            Text(if (isEdit) "Change your display name" else "What should others call you?", fontSize = 14.sp, color = textSec)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(16.dp)).border(1.5.dp, accent.copy(0.4f), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) {
                BasicTextField(
                    value = nameInput,
                    onValueChange = { if (it.length <= 24) nameInput = it },
                    textStyle = TextStyle(color = textPri, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 14.dp)) {
                            if (nameInput.isEmpty()) Text("e.g. Rahul, Alice…", color = textSec, fontSize = 18.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("${nameInput.length}/24", fontSize = 11.sp, color = textSec, modifier = Modifier.align(Alignment.End))
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isNotBlank()) {
                        saveName(trimmed)
                        nameInput = ""
                        screen = Screen.SCAN
                    }
                },
                enabled = nameInput.trim().isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(Modifier.fillMaxSize().background(if (nameInput.trim().isNotBlank()) Brush.linearGradient(listOf(accent, accentSec)) else Brush.linearGradient(listOf(textSec.copy(0.3f), textSec.copy(0.3f))), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text(if (isEdit) "Save Name  ✓" else "Let's Go  ➤", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
            if (isEdit) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { nameInput = ""; screen = Screen.SCAN }) { Text("Cancel", color = textSec) }
            }
        }
    }

    @Composable
    private fun ScanScreen(pulseA: Float) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF0D1B3E), Color(0xFF1A0D3E)))).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(Brush.linearGradient(listOf(accent, accentSec)), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("WaveChat", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = textPri, letterSpacing = 0.5.sp)
                            Text("Tap a device to connect", fontSize = 12.sp, color = accent)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("You are", fontSize = 10.sp, color = textSec)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(myName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(26.dp).background(accent.copy(0.12f), CircleShape).border(1.dp, accent.copy(0.3f), CircleShape).clickable { nameInput = myName; screen = Screen.SETUP }, contentAlignment = Alignment.Center) { Text("✎", color = accent, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(if (statusOk) success.copy(0.08f) else danger.copy(0.08f)).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).alpha(if (!statusOk) pulseA else 1f).background(if (statusOk) success else danger, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(status, fontSize = 13.sp, color = if (statusOk) success else danger, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEARBY ${currentScanMode.name} DEVICES", fontSize = 11.sp, color = textSec, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (currentScanMode == ScanMode.WIFI) {
                            if (!wifiDirectManager.isWifiEnabled()) {
                                setStatus("Please turn on Wi-Fi in settings", false)
                                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            } else {
                                setStatus("Scanning for Wi-Fi devices…", true)
                                wifiDirectManager.discoverPeers()
                            }
                        } else {
                            setStatus("Scanning BLE devices…", true)
                            discoveredBleDevices.clear()
                            seenAddresses.clear()
                            startBleScan()
                        }
                    }) {
                        Text("Refresh", color = textSec, fontSize = 12.sp)
                    }
                    
                    TextButton(onClick = { 
                        currentScanMode = if (currentScanMode == ScanMode.BLE) ScanMode.WIFI else ScanMode.BLE
                        if (currentScanMode == ScanMode.WIFI) {
                            if (!wifiDirectManager.isWifiEnabled()) {
                                setStatus("Please turn on Wi-Fi in settings", false)
                                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            } else {
                                setStatus("Scanning for Wi-Fi devices…", true)
                                wifiDirectManager.discoverPeers() 
                            }
                        } else {
                            setStatus("Scanning BLE devices…", true)
                            startBleScan()
                        }
                    }) {
                        Text("Switch to ${if (currentScanMode == ScanMode.BLE) "Wi-Fi" else "BLE"}", color = accentSec, fontSize = 12.sp)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val isEmpty = if (currentScanMode == ScanMode.BLE) discoveredBleDevices.isEmpty() else discoveredWifiDevices.isEmpty()
                if (isEmpty) {
                    item {
                        Box(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(16.dp)).padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(48.dp).alpha(pulseA).background(accent.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) { Text("📡", fontSize = 22.sp) }
                                Spacer(Modifier.height(12.dp))
                                Text("Searching for ${currentScanMode.name} devices…", color = textPri, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    if (currentScanMode == ScanMode.BLE) {
                        items(discoveredBleDevices, key = { it.address }) { device ->
                            val isSelected = selectedBlePeers.contains(device.address)
                            DeviceCard(device.name, "${device.rssi} dBm", isSelected = isSelected) { 
                                if (isSelected) selectedBlePeers.remove(device.address)
                                else selectedBlePeers.add(device.address)
                            }
                        }
                    } else {
                        items(discoveredWifiDevices, key = { it.device.deviceAddress }) { peer ->
                            DeviceCard(peer.customName, "Wi-Fi Direct", isSelected = false) { connectToWifiPeer(peer.device) }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
        
        if (currentScanMode == ScanMode.BLE && selectedBlePeers.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Button(
                    onClick = {
                        chatMessages.clear()
                        activeRecipientId = MeshMessage.BROADCAST_ID
                        selectedBlePeers.forEach { addr ->
                            discoveredBleDevices.find { it.address == addr }?.let { 
                                knownPeers[it.address] = it.name
                                connectToBlePeer(it) 
                            }
                        }
                        screen = Screen.CHAT
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, accentSec)), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("Mesh Chat (${selectedBlePeers.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(name: String, subtext: String, isSelected: Boolean, onTap: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(16.dp)).border(1.5.dp, if (isSelected) accent else accent.copy(0.1f), RoundedCornerShape(16.dp)).clickable { onTap() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(if (isSelected) accent else accent.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) { Text("📱", fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, color = textPri, fontWeight = FontWeight.SemiBold)
                Text(subtext, fontSize = 11.sp, color = textSec)
            }
            if (currentScanMode == ScanMode.BLE) {
                androidx.compose.material3.Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onTap() },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = textSec)
                )
            } else {
                Text("Tap to connect", fontSize = 11.sp, color = accentSec)
            }
        }
    }

    @Composable
    private fun ChatScreen(pulseA: Float) {
        androidx.activity.compose.BackHandler {
            goBackToScan()
        }
        
        var expanded by remember { mutableStateOf(false) }
        val chatTitle = if (currentScanMode == ScanMode.WIFI) {
            activeWifiPeerName.takeIf { it.isNotBlank() } ?: "Private Chat"
        } else {
            if (activeRecipientId == MeshMessage.BROADCAST_ID) "Mesh Chat (Everyone)"
            else "Private: ${knownPeers[activeRecipientId] ?: "Unknown"}"
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF0D1B3E), Color(0xFF1A0D3E)))).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(accent.copy(0.1f), CircleShape).border(1.dp, accent.copy(0.3f), CircleShape).clickable { goBackToScan() }, contentAlignment = Alignment.Center) { Text("←", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable(enabled = currentScanMode == ScanMode.BLE) { expanded = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chatTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPri)
                            if (currentScanMode == ScanMode.BLE) {
                                Text(" ▼", color = textPri, fontSize = 12.sp)
                            }
                        }
                        Text(if (isConnected) "Connected via ${currentScanMode.name}" else "Disconnected", fontSize = 11.sp, color = if (isConnected) success else textSec)
                        
                        if (currentScanMode == ScanMode.BLE) {
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(cardBg)) {
                                DropdownMenuItem(
                                    text = { Text("Mesh Chat (Everyone)", color = textPri) },
                                    onClick = { activeRecipientId = MeshMessage.BROADCAST_ID; expanded = false }
                                )
                                knownPeers.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = { Text("Private: $name", color = textPri) },
                                        onClick = { activeRecipientId = id; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(if (statusOk) success.copy(0.08f) else danger.copy(0.08f)).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).alpha(if (!statusOk) pulseA else 1f).background(if (statusOk) success else danger, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(status, fontSize = 12.sp, color = if (statusOk) success else danger)
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
                items(chatMessages.reversed()) { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start) {
                            if (!msg.isMe && msg.senderLabel.isNotEmpty()) {
                                Text(msg.senderLabel, fontSize = 10.sp, color = textSec, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                            Box(Modifier.widthIn(max = 280.dp).background(if (msg.isMe) Brush.linearGradient(listOf(accent, accentSec)) else Brush.linearGradient(listOf(cardBg, cardBg)), RoundedCornerShape(16.dp, 16.dp, if (msg.isMe) 4.dp else 16.dp, if (msg.isMe) 16.dp else 4.dp)).border(1.dp, if (!msg.isMe) accent.copy(0.1f) else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(msg.text, color = if (msg.isMe) Color.Black else textPri, fontSize = 15.sp, fontWeight = if (msg.isMe) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
                if (chatMessages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("💬", fontSize = 40.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("Say hello!", color = textPri, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(cardBg).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).background(Color(0xFF1E2D4A), RoundedCornerShape(24.dp)).border(1.dp, accent.copy(0.2f), RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        textStyle = TextStyle(color = textPri, fontSize = 15.sp),
                        decorationBox = { inner -> Box(Modifier.padding(vertical = 10.dp)) { if (messageText.isEmpty()) Text("Type a message…", color = textSec, fontSize = 15.sp); inner() } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(accent, accentSec)), CircleShape).clickable { sendMessage() }, contentAlignment = Alignment.Center) { Text("➤", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}