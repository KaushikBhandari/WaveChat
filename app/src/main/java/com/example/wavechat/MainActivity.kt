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
    // SCAN   = showing nearby devices, user selects who to chat with
    // CHAT   = chatting with selected device(s)
    enum class Screen { SCAN, CHAT }
    private var screen by mutableStateOf(Screen.SCAN)

    // ── State ──────────────────────────────────────────────────────
    private val discoveredDevices      = mutableStateListOf<BleDevice>()  // ALL nearby
    private val selectedAddresses      = mutableStateListOf<String>()      // user's picks
    private val connectedPeerAddresses = mutableStateListOf<String>()      // actually connected
    private val connectedGatts         = mutableMapOf<String, BluetoothGatt>()

    // Per-device chat history: address → list of messages
    private val chatHistories = mutableStateMapOf<String, MutableList<ChatMessage>>()

    // For group chat: all messages in one place
    private val groupMessages = mutableStateListOf<ChatMessage>()

    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val senderLabel: String = "",
        val isRelayed: Boolean = false,
        val hopCount: Int = 0
    )

    private var myAddress     by mutableStateOf("local")
    private var status        by mutableStateOf("Starting…")
    private var statusOk      by mutableStateOf(false)
    private var messageText   by mutableStateOf("")
    private var pendingCount  by mutableStateOf(0)
    private var debugLog      by mutableStateOf("")
    private var showPermDialog by mutableStateOf(false)
    private var permDialogMsg  by mutableStateOf("")

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID    = UUID.fromString("abcdef01-1234-1234-1234-abcdefabcdef")

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter get() = bluetoothManager.adapter
    private lateinit var meshRouter: MeshRouter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var gattServer: BluetoothGattServer? = null

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
                    discoveredDevices.clear()
                    connectedPeerAddresses.clear()
                    seenAddresses.clear()
                    // Reset so startBle can run again when BT comes back on
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
                "Some permissions were permanently denied. Please go to Settings → Apps → WaveChat → Permissions and enable Location and Bluetooth."
            else
                "WaveChat needs Bluetooth and Location permissions to find nearby devices. Please allow all permissions."
            showPermDialog = true
            if (!permanentlyDenied)
                mainHandler.postDelayed({ checkAndRequestPermissions() }, 500)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meshRouter = MeshRouter("local") { msg, addr -> sendViaBle(msg, addr) }
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
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        try { scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) {}
        try { gattServer?.close(); gattServer = null } catch (_: Exception) {}
        try { connectedGatts.values.forEach { it.close() } } catch (_: Exception) {}
    }

    // ── Permissions ────────────────────────────────────────────────
    private fun has(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
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
        if (!adapter.isEnabled) {
            setStatus("Bluetooth is OFF", false)
            try {
                @Suppress("DEPRECATION")
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) { dbg("BT enable failed: ${e.message}") }
        } else { setStatus("Scanning for nearby devices…", true); startBle() }
    }

    // Guard so startBle never runs twice
    private var bleStarted = false
    private fun startBle() {
        if (bleStarted) { dbg("startBle already running — skipping"); return }
        bleStarted = true
        dbg("startBle — starting GATT, advertising, scan")
        startGattServer()
        startAdvertising()
        startBleScan()
    }

    // ── Advertising ────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        try {
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true).setTimeout(0).build(),
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false).build(),
                object : AdvertiseCallback() {
                    override fun onStartSuccess(s: AdvertiseSettings) { dbg("Advertising OK") }
                    override fun onStartFailure(c: Int) { dbg("Advertising FAILED $c") }
                }
            )
        } catch (e: SecurityException) { dbg("Adv SE: ${e.message}") }
    }

    // ── BLE Scan — deduplicated with synchronized address set ───────
    // Synchronized set so scan thread and UI thread don't race
    private val seenAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    private fun friendlyName(addr: String): String {
        val suffix = addr.replace(":", "").takeLast(4).uppercase()
        return "Device $suffix"
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        dbg("startBleScan — waiting 1s…")
        mainHandler.postDelayed({
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                dbg("Scanner null — retry in 2s")
                mainHandler.postDelayed({ startBleScan() }, 2000); return@postDelayed
            }
            scanCallback?.let { try { scanner.stopScan(it) } catch (_: Exception) {} }

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val addr = result.device.address
                    if (addr == myAddress) return  // skip self

                    val isWaveChat = result.scanRecord?.serviceUuids
                        ?.contains(ParcelUuid(SERVICE_UUID)) == true
                    if (!isWaveChat) return

                    val device = BleDevice(
                        name     = friendlyName(addr),
                        address  = addr,
                        rssi     = result.rssi,
                        lastSeen = System.currentTimeMillis()
                    )

                    runOnUiThread {
                        val existing = discoveredDevices.indexOfFirst { it.address == addr }
                        if (existing >= 0) {
                            // just update rssi silently — no duplicate added
                            discoveredDevices[existing] = device
                        } else {
                            // only add if truly new address
                            if (seenAddresses.add(addr)) {
                                discoveredDevices.add(device)
                                dbg("New device: ${friendlyName(addr)} ($addr)")
                            }
                        }
                    }
                }
                override fun onScanFailed(code: Int) {
                    dbg("Scan FAILED $code")
                    runOnUiThread { setStatus("Scan failed ($code) — restart app", false) }
                }
            }
            scanCallback = cb
            try {
                // Use SERVICE_UUID filter to only get WaveChat devices
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
                dbg("✅ Scan started with filter")
                runOnUiThread { setStatus("Scanning… select devices to chat with", true) }
            } catch (e: Exception) { dbg("Scan ex: ${e.message}") }
        }, 1000)
    }

    // ── GATT Server ────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) { dbg("GATT server already running — skipping"); return }
        try {
            gattServer = bluetoothManager.openGattServer(this,
                object : BluetoothGattServerCallback() {
                    override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice, requestId: Int,
                        characteristic: BluetoothGattCharacteristic,
                        preparedWrite: Boolean, responseNeeded: Boolean,
                        offset: Int, value: ByteArray
                    ) {
                        dbg("Write request from ${device.address} size=${value.size} responseNeeded=$responseNeeded")

                        // Always send response FIRST before processing (unblocks sender)
                        if (responseNeeded) try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                            dbg("Sent GATT_SUCCESS response to ${device.address}")
                        } catch (e: SecurityException) {
                            dbg("sendResponse SE: ${e.message}")
                        }

                        // Accept from ANY connected device (not just selected ones)
                        // Selection is only for sending, not receiving
                        meshRouter.onMessageReceived(value, device.address,
                            connectedPeerAddresses.toList()) { msg ->
                            runOnUiThread {
                                val label = friendlyName(device.address)
                                val chatMsg = ChatMessage(
                                    text        = msg.text,
                                    isMe        = false,
                                    senderLabel = label,
                                    isRelayed   = msg.hopCount > 0,
                                    hopCount    = msg.hopCount
                                )
                                chatHistories.getOrPut(device.address) { mutableListOf() }.add(chatMsg)
                                groupMessages.add(chatMsg)
                                setStatus("Message from $label", true)
                                pendingCount = meshRouter.pendingCount()
                                dbg("✅ Message added to UI: ${msg.text}")
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
            dbg("GATT server ready with WRITE + WRITE_NO_RESPONSE")
        } catch (e: SecurityException) { dbg("GATT SE: ${e.message}") }
    }

    // Track which peers have completed service discovery (ready to send)
    private val servicesReadySet = Collections.synchronizedSet(mutableSetOf<String>())
    // Queue messages if services not ready yet
    private val sendQueue = mutableMapOf<String, MutableList<MeshMessage>>()

    // ── Connect only to selected devices ───────────────────────────
    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BleDevice) {
        if (connectedGatts.containsKey(device.address)) return
        dbg("Connecting to ${device.address}")
        try {
            val gatt = bluetoothAdapter!!.getRemoteDevice(device.address)
                .connectGatt(this, false, object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
                        dbg("ConnState ${device.address} newState=$newState status=$st")
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedGatts[device.address] = g
                                runOnUiThread {
                                    if (!connectedPeerAddresses.contains(device.address))
                                        connectedPeerAddresses.add(device.address)
                                    setStatus("Connected — setting up…", true)
                                }
                                // Step 1: request larger MTU first (improves reliability)
                                mainHandler.postDelayed({
                                    try {
                                        dbg("Requesting MTU for ${device.address}")
                                        g.requestMtu(512)
                                    } catch (e: SecurityException) {
                                        // If MTU fails, go straight to service discovery
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
                                    connectedPeerAddresses.remove(device.address)
                                    setStatus(
                                        if (connectedPeerAddresses.isEmpty()) "Scanning…"
                                        else "Connected to ${connectedPeerAddresses.size} peer(s)",
                                        connectedPeerAddresses.isNotEmpty()
                                    )
                                }
                            }
                        }
                    }

                    // Step 2: after MTU negotiated, discover services
                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        dbg("MTU changed to $mtu for ${device.address} — now discovering services")
                        mainHandler.postDelayed({
                            try { g.discoverServices() } catch (e: SecurityException) {
                                dbg("discoverServices SE: ${e.message}")
                            }
                        }, 300)
                    }

                    // Step 3: services ready — now safe to send messages
                    override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                        dbg("onServicesDiscovered ${device.address} status=$st")
                        if (st != BluetoothGatt.GATT_SUCCESS) {
                            dbg("Service discovery FAILED status=$st — retrying in 1s")
                            mainHandler.postDelayed({
                                try { g.discoverServices() } catch (_: Exception) {}
                            }, 1000)
                            return
                        }
                        // Log all found services for debugging
                        g.services.forEach { svc ->
                            dbg("Found service: ${svc.uuid}")
                            svc.characteristics.forEach { char ->
                                dbg("  Found char: ${char.uuid}")
                            }
                        }
                        val ourService = g.getService(SERVICE_UUID)
                        if (ourService == null) {
                            dbg("WaveChat service NOT found on ${device.address}")
                            runOnUiThread {
                                setStatus("Service not found on ${friendlyName(device.address)}", false)
                            }
                            return
                        }
                        dbg("✅ WaveChat service found — ready to send!")
                        servicesReadySet.add(device.address)
                        runOnUiThread {
                            setStatus("Ready to chat with ${connectedPeerAddresses.size} peer(s)!", true)
                            // Flush any queued messages
                            sendQueue[device.address]?.forEach { msg ->
                                dbg("Flushing queued msg to ${device.address}")
                                sendViaBle(msg, device.address)
                            }
                            sendQueue.remove(device.address)
                        }
                    }
                }, BluetoothDevice.TRANSPORT_LE)
            connectedGatts[device.address] = gatt
        } catch (e: SecurityException) { dbg("Connect SE: ${e.message}") }
    }

    // ── Disconnect from a specific peer ───────────────────────────
    @SuppressLint("MissingPermission")
    private fun disconnectPeer(address: String) {
        dbg("Disconnecting $address")
        connectedGatts[address]?.let {
            try { it.disconnect(); it.close() } catch (_: SecurityException) {}
        }
        connectedGatts.remove(address)
        connectedPeerAddresses.remove(address)
        selectedAddresses.remove(address)
    }

    // ── Toggle device selection ────────────────────────────────────
    private fun toggleSelect(device: BleDevice) {
        if (selectedAddresses.contains(device.address)) {
            // Deselect → disconnect
            selectedAddresses.remove(device.address)
            disconnectPeer(device.address)
        } else {
            // Select → connect
            selectedAddresses.add(device.address)
            chatHistories.getOrPut(device.address) { mutableListOf() }
            connectToPeer(device)
        }
    }

    // ── Start chat with selected devices ───────────────────────────
    private fun startChat() {
        if (selectedAddresses.isEmpty()) return
        screen = Screen.CHAT
        setStatus("Chatting with ${selectedAddresses.size} device(s)", true)
    }

    // ── Go back to scan screen — disconnect all ────────────────────
    private fun goBackToScan() {
        screen = Screen.SCAN
        // Disconnect all selected peers
        selectedAddresses.toList().forEach { disconnectPeer(it) }
        selectedAddresses.clear()
        groupMessages.clear()
        chatHistories.clear()
        setStatus("Scanning… select devices to chat with", true)
    }

    // ── Send via BLE ───────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun sendViaBle(msg: MeshMessage, peerAddress: String) {
        val gatt = connectedGatts[peerAddress] ?: run {
            dbg("sendViaBle: no gatt for $peerAddress")
            return
        }
        // If services not ready yet, queue the message
        if (!servicesReadySet.contains(peerAddress)) {
            dbg("Services not ready for $peerAddress — queuing message")
            sendQueue.getOrPut(peerAddress) { mutableListOf() }.add(msg)
            return
        }
        val svc  = gatt.getService(SERVICE_UUID) ?: run {
            dbg("sendViaBle: service not found for $peerAddress")
            return
        }
        val char = svc.getCharacteristic(CHAR_UUID) ?: return
        val bytes = msg.toBytes()
        if (bytes.size > 512) return
        try {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            char.value = bytes
            val ok = gatt.writeCharacteristic(char)
            dbg("writeCharacteristic to $peerAddress result=$ok writeType=${char.writeType}")
        } catch (e: SecurityException) { dbg("Write SE: ${e.message}") }
    }

    private fun sendMessage() {
        if (messageText.isBlank()) return
        val text = messageText.trim()
        // Send only to selected+connected peers
        val targets = connectedPeerAddresses.filter { selectedAddresses.contains(it) }
        val msg = meshRouter.originateMessage(text, targets)
        val chatMsg = ChatMessage(text, true, "Me")
        targets.forEach { addr ->
            chatHistories.getOrPut(addr) { mutableListOf() }.add(chatMsg)
        }
        groupMessages.add(chatMsg)
        pendingCount = meshRouter.pendingCount()
        messageText = ""
        setStatus(if (targets.isEmpty()) "No peers connected — queued" else "Message sent!", true)
    }

    // ══════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════
    // Colors
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
        val pulseA by pulse.animateFloat(0.3f, 1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")

        // Permission dialog
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

        Box(Modifier.fillMaxSize().background(darkBg)) {
            when (screen) {
                Screen.SCAN -> ScanScreen(pulseA)
                Screen.CHAT -> ChatScreen(pulseA)
            }
        }
    }

    // ── SCAN SCREEN ────────────────────────────────────────────────
    @Composable
    private fun ScanScreen(pulseA: Float) {
        Column(Modifier.fillMaxSize()) {

            // Header
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0D1B3E), Color(0xFF1A0D3E))))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp)
                            .background(Brush.linearGradient(listOf(accent, accentSec)), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("WaveChat", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                color = textPri, letterSpacing = 0.5.sp)
                            Text("Select who to chat with", fontSize = 12.sp, color = accent)
                        }
                    }
                }
            }

            // Status
            Row(
                Modifier.fillMaxWidth()
                    .background(if (statusOk) success.copy(0.08f) else danger.copy(0.08f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).alpha(if (!statusOk) pulseA else 1f)
                    .background(if (statusOk) success else danger, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(status, fontSize = 13.sp, color = if (statusOk) success else danger,
                    fontWeight = FontWeight.Medium)
            }

            // Section title
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEARBY DEVICES", fontSize = 11.sp, color = textSec,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    if (discoveredDevices.isEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.alpha(pulseA)
                            .background(accent.copy(0.15f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("scanning", fontSize = 10.sp, color = accent) }
                    }
                }
                if (selectedAddresses.isNotEmpty()) {
                    Text("${selectedAddresses.size} selected", fontSize = 12.sp,
                        color = accentSec, fontWeight = FontWeight.SemiBold)
                }
            }

            // Device list
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (discoveredDevices.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth()
                                .background(cardBg, RoundedCornerShape(16.dp))
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(48.dp).alpha(pulseA)
                                    .background(accent.copy(0.1f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Text("📡", fontSize = 22.sp) }
                                Spacer(Modifier.height(12.dp))
                                Text("Searching for devices…", color = textPri,
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text("Open WaveChat on another phone nearby",
                                    color = textSec, fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                items(discoveredDevices, key = { it.address }) { device ->
                    val isSelected  = selectedAddresses.contains(device.address)
                    val isConnected = connectedPeerAddresses.contains(device.address)
                    DeviceCard(device, isSelected, isConnected) { toggleSelect(device) }
                }
                item { Spacer(Modifier.height(100.dp)) }
            }

            // Bottom bar — Start Chat button
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, darkBg)))
                    .padding(20.dp)
            ) {
                if (selectedAddresses.isEmpty()) {
                    // Hint text
                    Box(
                        Modifier.fillMaxWidth()
                            .background(cardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, accent.copy(0.1f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tap a device above to select it",
                            color = textSec, fontSize = 14.sp)
                    }
                } else {
                    Button(
                        onClick = { startChat() },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(
                                    Brush.linearGradient(listOf(accent, accentSec)),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (selectedAddresses.size == 1)
                                    "Start Private Chat  ➤"
                                else
                                    "Start Group Chat with ${selectedAddresses.size}  ➤",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(
        device: BleDevice,
        isSelected: Boolean,
        isConnected: Boolean,
        onTap: () -> Unit
    ) {
        val borderColor = when {
            isSelected && isConnected -> success
            isSelected               -> accent
            else                     -> accent.copy(0.1f)
        }
        val bgColor = when {
            isSelected && isConnected -> success.copy(0.08f)
            isSelected               -> accent.copy(0.06f)
            else                     -> cardBg
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
                .clickable { onTap() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox-style indicator
            Box(
                Modifier.size(24.dp)
                    .background(
                        if (isSelected) Brush.linearGradient(listOf(accent, accentSec))
                        else Brush.linearGradient(listOf(cardBg, cardBg)),
                        RoundedCornerShape(6.dp)
                    )
                    .border(1.5.dp,
                        if (isSelected) Color.Transparent else accent.copy(0.3f),
                        RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected)
                    Text("✓", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(14.dp))

            // Device icon
            Box(
                Modifier.size(44.dp)
                    .background(accent.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("📱", fontSize = 20.sp) }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 15.sp, color = textPri, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Signal bars
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Bottom) {
                        repeat(3) { i ->
                            val filled = when {
                                device.rssi > -60 -> true
                                device.rssi > -75 -> i < 2
                                else -> i < 1
                            }
                            Box(Modifier.width(3.dp).height((5 + i * 4).dp)
                                .background(
                                    if (filled) accent else accent.copy(0.2f),
                                    RoundedCornerShape(1.dp)
                                ))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("${device.rssi} dBm", fontSize = 11.sp, color = textSec)
                }
            }

            // Status badge
            Box(
                Modifier
                    .background(
                        when {
                            isConnected -> success.copy(0.15f)
                            isSelected  -> accent.copy(0.1f)
                            else        -> Color.Transparent
                        },
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    when {
                        isConnected -> "Connected"
                        isSelected  -> "Connecting…"
                        else        -> ""
                    },
                    fontSize = 11.sp,
                    color = if (isConnected) success else accent,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // ── CHAT SCREEN ────────────────────────────────────────────────
    @Composable
    private fun ChatScreen(pulseA: Float) {
        val chatTitle = if (selectedAddresses.size == 1)
            discoveredDevices.find { it.address == selectedAddresses[0] }?.name ?: "Private Chat"
        else "Group Chat (${selectedAddresses.size})"

        Column(Modifier.fillMaxSize()) {

            // Chat header
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0D1B3E), Color(0xFF1A0D3E))))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Back button
                    Box(
                        Modifier.size(36.dp)
                            .background(accent.copy(0.1f), CircleShape)
                            .border(1.dp, accent.copy(0.3f), CircleShape)
                            .clickable { goBackToScan() },
                        contentAlignment = Alignment.Center
                    ) { Text("←", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(chatTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPri)
                        Text(
                            "${connectedPeerAddresses.size}/${selectedAddresses.size} connected",
                            fontSize = 11.sp, color = if (connectedPeerAddresses.isNotEmpty()) success else textSec
                        )
                    }

                    // Connected devices avatars
                    Row {
                        selectedAddresses.take(3).forEach { addr ->
                            val connected = connectedPeerAddresses.contains(addr)
                            Box(
                                Modifier.size(32.dp).offset(x = (-4).dp)
                                    .background(
                                        if (connected) success.copy(0.2f) else textSec.copy(0.1f),
                                        CircleShape
                                    )
                                    .border(1.5.dp,
                                        if (connected) success else textSec.copy(0.3f),
                                        CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("📱", fontSize = 14.sp) }
                        }
                    }
                }
            }

            // Status bar
            Row(
                Modifier.fillMaxWidth()
                    .background(if (statusOk) success.copy(0.08f) else danger.copy(0.08f))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).alpha(if (!statusOk) pulseA else 1f)
                    .background(if (statusOk) success else danger, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(status, fontSize = 12.sp, color = if (statusOk) success else danger)
                if (pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("• $pendingCount queued", fontSize = 11.sp, color = Color(0xFFFFB74D))
                }
            }

            // Messages
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true
            ) {
                items(groupMessages.reversed()) { msg ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Column(horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start) {
                            if (!msg.isMe && msg.senderLabel.isNotEmpty()) {
                                Text(msg.senderLabel, fontSize = 10.sp, color = textSec,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                            Box(
                                Modifier
                                    .widthIn(max = 280.dp)
                                    .background(
                                        when {
                                            msg.isMe      -> Brush.linearGradient(listOf(accent, accentSec))
                                            msg.isRelayed -> Brush.linearGradient(listOf(Color(0xFF4A1B8A), Color(0xFF7B1FA2)))
                                            else          -> Brush.linearGradient(listOf(cardBg, cardBg))
                                        },
                                        RoundedCornerShape(
                                            16.dp, 16.dp,
                                            if (msg.isMe) 4.dp else 16.dp,
                                            if (msg.isMe) 16.dp else 4.dp
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        if (!msg.isMe && !msg.isRelayed) accent.copy(0.1f) else Color.Transparent,
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(msg.text,
                                    color = if (msg.isMe) Color.Black else textPri,
                                    fontSize = 15.sp,
                                    fontWeight = if (msg.isMe) FontWeight.Medium else FontWeight.Normal)
                            }
                            if (msg.isRelayed) {
                                Text("relayed ×${msg.hopCount}", fontSize = 10.sp,
                                    color = accentSec,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                if (groupMessages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("💬", fontSize = 40.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("Say hello!", color = textPri, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("Messages are end-to-end via BLE mesh",
                                    color = textSec, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Input bar
            Row(
                Modifier.fillMaxWidth()
                    .background(cardBg)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f)
                        .background(Color(0xFF1E2D4A), RoundedCornerShape(24.dp))
                        .border(1.dp, accent.copy(0.2f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        textStyle = TextStyle(color = textPri, fontSize = 15.sp),
                        decorationBox = { inner ->
                            Box(Modifier.padding(vertical = 10.dp)) {
                                if (messageText.isEmpty())
                                    Text("Type a message…", color = textSec, fontSize = 15.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(48.dp)
                        .background(
                            Brush.linearGradient(listOf(accent, accentSec)), CircleShape)
                        .clickable { sendMessage() },
                    contentAlignment = Alignment.Center
                ) { Text("➤", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}