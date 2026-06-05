package com.example.wavechat

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class WifiDirectManager(
    private val context: Context,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit,
    private val onServiceFound: (device: WifiP2pDevice, customName: String) -> Unit,
    private val onConnectionInfoAvailable: (isGroupOwner: Boolean, groupOwnerAddress: String) -> Unit,
    private val onMessageReceived: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val manager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var outputStream: PrintWriter? = null
    private var inputStream: BufferedReader? = null
    @Volatile private var isRunning = false

    fun isWifiEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        return wifiManager.isWifiEnabled
    }

    fun init() {
        if (manager != null) {
            channel = manager?.initialize(context, Looper.getMainLooper(), null)
            
            manager?.setDnsSdResponseListeners(channel,
                { instanceName, registrationType, srcDevice ->
                    // Service discovered
                },
                { fullDomainName, record, srcDevice ->
                    val customName = record["name"] ?: srcDevice.deviceName
                    onServiceFound(srcDevice, customName)
                }
            )

            receiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                                Log.d("WifiDirect", "Wi-Fi P2P is disabled")
                            }
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            manager?.requestPeers(channel) { peers ->
                                onPeersChanged(peers.deviceList.toList())
                            }
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                            if (networkInfo?.isConnected == true) {
                                manager?.requestConnectionInfo(channel) { info ->
                                    val groupOwnerAddress = info.groupOwnerAddress?.hostAddress ?: ""
                                    onConnectionInfoAvailable(info.isGroupOwner, groupOwnerAddress)
                                    if (info.groupFormed) {
                                        if (info.isGroupOwner) {
                                            startServer()
                                        } else {
                                            startClient(groupOwnerAddress)
                                        }
                                    }
                                }
                            } else {
                                closeSocket()
                                onDisconnected()
                            }
                        }
                    }
                }
            }
            context.registerReceiver(receiver, intentFilter)
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WifiDirect", "Discovery initiated")
            }
            override fun onFailure(reasonCode: Int) {
                Log.e("WifiDirect", "Discovery failed: $reasonCode")
            }
        })
        
        manager?.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
                manager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        manager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Log.d("WifiDirect", "Service discovery initiated")
                            }
                            override fun onFailure(reasonCode: Int) {
                                Log.e("WifiDirect", "Service discovery failed: $reasonCode")
                            }
                        })
                    }
                    override fun onFailure(reasonCode: Int) {
                        Log.e("WifiDirect", "Add service request failed: $reasonCode")
                    }
                })
            }
            override fun onFailure(reasonCode: Int) {}
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WifiDirect", "Connecting to ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "Connect failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
        closeSocket()
    }

    fun cleanUp() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error unregistering receiver", e)
        }
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun startAdvertisingService(name: String) {
        if (manager == null || channel == null) return
        val record = mapOf("name" to name)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("WaveChatService", "_presence._tcp", record)
        
        manager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d("WifiDirect", "Service advertising started with name: $name")
                    }
                    override fun onFailure(reasonCode: Int) {
                        Log.e("WifiDirect", "Failed to add local service: $reasonCode")
                    }
                })
            }
            override fun onFailure(reasonCode: Int) {}
        })
    }

    @SuppressLint("MissingPermission")
    fun setDeviceName(name: String) {
        startAdvertisingService(name)
        if (manager == null || channel == null) return
        try {
            val method = manager?.javaClass?.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method?.invoke(manager, channel, name, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Device name set to $name")
                }
                override fun onFailure(reason: Int) {
                    Log.e("WifiDirect", "Failed to set device name: $reason")
                }
            })
        } catch (e: Exception) {
            Log.e("WifiDirect", "Reflection failed for setDeviceName", e)
        }
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(8888)
                Log.d("WifiDirect", "Server waiting for connection...")
                socket = serverSocket?.accept()
                Log.d("WifiDirect", "Server accepted connection")
                setupStreams(socket!!)
            } catch (e: Exception) {
                Log.e("WifiDirect", "Server error", e)
                isRunning = false
            }
        }
    }

    private fun startClient(hostAddress: String) {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                socket = Socket()
                Log.d("WifiDirect", "Client connecting to $hostAddress:8888...")
                socket?.connect(InetSocketAddress(hostAddress, 8888), 5000)
                Log.d("WifiDirect", "Client connected")
                setupStreams(socket!!)
            } catch (e: Exception) {
                Log.e("WifiDirect", "Client error", e)
                isRunning = false
            }
        }
    }

    private fun setupStreams(s: Socket) {
        try {
            inputStream = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            outputStream = PrintWriter(s.getOutputStream(), true)
            
            // Handshake: Send our name immediately
            val myName = context.getSharedPreferences("wavechat", Context.MODE_PRIVATE).getString("myName", "Device") ?: "Device"
            val pubKey = CryptoUtils.getMyPublicKeyString()
            sendMessage("NAME_HANDSHAKE:$myName|PUBKEY:$pubKey")
            
            readLoop()
        } catch (e: Exception) {
            Log.e("WifiDirect", "Stream setup error", e)
            isRunning = false
            closeSocket()
        }
    }

    private fun readLoop() {
        while (isRunning && socket?.isConnected == true) {
            try {
                val message = inputStream?.readLine()
                if (message == null) {
                    break
                }
                onMessageReceived(message)
            } catch (e: Exception) {
                Log.e("WifiDirect", "Read error", e)
                break
            }
        }
        isRunning = false
        closeSocket()
        onDisconnected()
    }

    fun sendMessage(msg: String) {
        thread {
            try {
                outputStream?.println(msg)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e("WifiDirect", "Write error", e)
            }
        }
    }

    private fun closeSocket() {
        isRunning = false
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        socket = null
        serverSocket = null
        inputStream = null
        outputStream = null
    }
}
