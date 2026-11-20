package com.example.esp32bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var listening = false
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val log = StringBuilder()
    private val permissionRequest = 41
    private var receiverRegistered = false
    private var discovering = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) addDevice(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discovering = false
                    refreshButton.isEnabled = true
                    if (!listening) statusText.text = if (devices.isEmpty()) {
                        "Busca concluída. Deixe o outro celular visível e tente novamente."
                    } else "${devices.size} dispositivo(s) encontrado(s) ou pareado(s)"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        deviceText = findViewById(R.id.deviceText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)
        refreshButton = findViewById(R.id.refreshButton)
        connectButton.setOnClickListener { toggleReception() }
        refreshButton.setOnClickListener { requestPermissionsAndDiscover() }

        findViewById<TextView>(R.id.clearButton).setOnClickListener {
            log.clear()
            logText.text = "Aguardando dados do dispositivo…"
        }

        if (adapter == null) {
            statusText.text = "Bluetooth não disponível neste celular"
            connectButton.isEnabled = false
            refreshButton.isEnabled = false
        } else ensurePermissionAndLoad()
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionAndLoad() {
        if (!hasPermissions()) requestPermissions(requiredPermissions(), permissionRequest)
        else {
            loadPairedDevices()
            startReception()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionRequest) return
        if (hasPermissions()) {
            loadPairedDevices()
            startReception()
            if (discovering) startDiscovery()
        } else statusText.text = "Permissões Bluetooth e de dispositivos próximos são necessárias"
    }

    private fun loadPairedDevices() {
        try {
            adapter?.bondedDevices.orEmpty().forEach(::addDevice)
            updateDeviceList()
            if (!listening) statusText.text = if (devices.isEmpty()) {
                "Nenhum dispositivo pareado. Toque em Atualizar dispositivos próximos."
            } else "${devices.size} dispositivo(s) pareado(s)"
        } catch (_: SecurityException) {
            statusText.text = "Permissão Bluetooth não concedida"
        }
    }

    private fun addDevice(device: BluetoothDevice) {
        try { devices[device.address] = device } catch (_: SecurityException) { }
        updateDeviceList()
    }

    private fun updateDeviceList() {
        val currentDevices = devices.values.toList()
        val selectedAddress = currentDevices.getOrNull(deviceSpinner.selectedItemPosition)?.address
        val sorted = currentDevices.sortedBy { safeName(it).lowercase(Locale.getDefault()) }
        devices.clear()
        sorted.forEach { devices[it.address] = it }
        val labels = sorted.map { "${safeName(it)}  •  ${it.address}" }.ifEmpty { listOf("Nenhum dispositivo encontrado") }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = sorted.indexOfFirst { it.address == selectedAddress }
        if (selectedIndex >= 0) deviceSpinner.setSelection(selectedIndex)
    }

    private fun requestPermissionsAndDiscover() {
        if (adapter?.isEnabled != true) {
            statusText.text = "Ative o Bluetooth nas configurações do celular"
            return
        }
        if (!hasPermissions()) {
            discovering = true
            requestPermissions(requiredPermissions(), permissionRequest)
            return
        }
        startDiscovery()
    }

    private fun startDiscovery() {
        try {
            devices.clear()
            updateDeviceList()
            adapter?.bondedDevices?.forEach(::addDevice)
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(discoveryReceiver, filter)
                }
                receiverRegistered = true
            }
            adapter?.cancelDiscovery()
            if (adapter?.startDiscovery() == true) {
                discovering = true
                refreshButton.isEnabled = false
                statusText.text = "Procurando… mantenha o outro celular visível"
            } else statusText.text = "Não foi possível iniciar a busca Bluetooth"
        } catch (_: SecurityException) {
            statusText.text = "Permissão de busca Bluetooth não concedida"
        }
    }

    private fun toggleReception() {
        if (listening) {
            listening = false
            try { serverSocket?.close() } catch (_: IOException) { }
            try { socket?.close() } catch (_: IOException) { }
            socket = null
            deviceText.text = "Nenhum dispositivo conectado"
            connectButton.text = "Iniciar recepção"
            statusText.text = "Recepção encerrada"
            return
        }
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), permissionRequest)
            return
        }
        startReception()
    }

    private fun startReception() {
        if (adapter?.isEnabled != true) {
            statusText.text = "Ative o Bluetooth para iniciar a recepção"
            return
        }
        if (!hasPermissions() || listening) return
        listening = true
        connectButton.text = "Parar recepção"
        connectButton.isEnabled = true
        statusText.text = "Aguardando conexão Bluetooth SPP neste celular"
        thread(name = "bluetooth-spp-server") {
            try {
                val server = adapter!!.listenUsingRfcommWithServiceRecord("ESP32 Monitor", sppUuid)
                serverSocket = server
                while (listening) {
                    val incoming = server.accept()
                    if (!listening) {
                        incoming.close()
                        break
                    }
                    socket = incoming
                    val remoteName = try { incoming.remoteDevice.name ?: incoming.remoteDevice.address }
                    catch (_: SecurityException) { "Dispositivo Bluetooth" }
                    runOnUiThread {
                        deviceText.text = remoteName
                        statusText.text = "Conectado • recebendo dados"
                        appendLog("Conectado a $remoteName")
                    }
                    readIncoming(incoming)
                }
            } catch (e: Exception) {
                if (listening) runOnUiThread {
                    statusText.text = "Falha ao iniciar recepção: ${e.message ?: "verifique o Bluetooth"}"
                }
            } finally {
                listening = false
                try { serverSocket?.close() } catch (_: IOException) { }
                serverSocket = null
                runOnUiThread {
                    connectButton.text = "Iniciar recepção"
                    connectButton.isEnabled = true
                }
            }
        }
    }

    private fun readIncoming(activeSocket: BluetoothSocket) {
        val buffer = ByteArray(1024)
        val pending = StringBuilder()
        try {
            val input = activeSocket.inputStream
            while (listening && activeSocket.isConnected) {
                val count = input.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val character = (buffer[i].toInt() and 0xff).toChar()
                    if (character == '\n' || character == '\r') {
                        if (pending.isNotEmpty()) {
                            val line = pending.toString().trim()
                            pending.clear()
                            if (line.isNotEmpty()) runOnUiThread { appendLog(line) }
                        }
                    } else if (pending.length < 4096) pending.append(character)
                }
            }
        } catch (e: Exception) {
            if (listening) runOnUiThread {
                statusText.text = "Conexão encerrada: ${e.message ?: "canal fechado"}"
            }
        }
        if (socket === activeSocket) {
            socket = null
            runOnUiThread {
                deviceText.text = "Nenhum dispositivo conectado"
                if (listening) statusText.text = "Aguardando conexão Bluetooth SPP neste celular"
            }
        }
    }

    private fun appendLog(message: String) {
        if (log.isEmpty()) logText.text = ""
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.append("[$time]  $message\n")
        if (log.length > 100_000) log.delete(0, log.length - 80_000)
        logText.text = log.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: "Dispositivo Bluetooth"
    } catch (_: SecurityException) {
        "Dispositivo Bluetooth"
    }

    override fun onDestroy() {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) { }
        listening = false
        try { serverSocket?.close() } catch (_: IOException) { }
        try { socket?.close() } catch (_: IOException) { }
        if (receiverRegistered) {
            try { unregisterReceiver(discoveryReceiver) } catch (_: IllegalArgumentException) { }
        }
        super.onDestroy()
    }
}
