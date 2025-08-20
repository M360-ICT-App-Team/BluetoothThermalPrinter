package com.peoplewareinnovations.bluetooth_thermal_printer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.*

private const val TAG = "BluetoothThermalPrinter"

class BluetoothThermalPrinterPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var outputStream: OutputStream? = null
    private var macAddress: String = ""
    private var state: String = "false"

    // This static function is optional and equivalent to onAttachedToEngine.
    // It supports the old pre-Flutter-1.12 Android projects.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "bluetooth_thermal_printer")
            val plugin = BluetoothThermalPrinterPlugin()
            plugin.context = registrar.context()
            channel.setMethodCallHandler(plugin)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "bluetooth_thermal_printer")
        channel.setMethodCallHandler(this)
        context = binding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

            "getBatteryLevel" -> {
                val batteryLevel = getBatteryLevel()
                if (batteryLevel != -1) result.success(batteryLevel)
                else result.error("UNAVAILABLE", "Battery level not available.", null)
            }

            "BluetoothStatus" -> {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val isOn = adapter?.isEnabled == true
                result.success(isOn.toString())
            }

            "connectPrinter" -> {
                macAddress = call.arguments.toString()
                if (macAddress.isEmpty()) {
                    result.success("false")
                    return
                }

                GlobalScope.launch(Dispatchers.Main) {
                    if (outputStream == null) {
                        outputStream = connect()?.also { Log.d(TAG, "Connected to printer") }
                        result.success(state)
                    }
                }
            }

            "disconnectPrinter" -> {
                GlobalScope.launch(Dispatchers.Main) {
                    if (outputStream != null) {
                        outputStream = disconnect()?.also { Log.d(TAG, "Disconnected printer") }
                        result.success("true")
                    }
                }
            }

            "writeBytes" -> {
                val byteList = call.arguments as? List<Int> ?: emptyList()
                var bytes = "\n".toByteArray()
                byteList.forEach { bytes += it.toByte() }

                if (outputStream != null) {
                    try {
                        outputStream?.write(bytes)
                        result.success("true")
                    } catch (e: Exception) {
                        outputStream = null
                        ShowToast("Device was disconnected, reconnect")
                        result.success("false")
                    }
                } else {
                    result.success("false")
                }
            }

            "printText" -> {
                val text = call.arguments.toString()
                if (outputStream != null) {
                    try {
                        val size = 2
                        outputStream?.write(setBytes.size[size])
                        outputStream?.write(text.toByteArray(Charsets.ISO_8859_1))
                        result.success("true")
                    } catch (e: Exception) {
                        outputStream = null
                        ShowToast("Device was disconnected, reconnect")
                        result.success("false")
                    }
                } else result.success("false")
            }

            "bluetothLinked" -> {
                result.success(getLinkedDevices())
            }

            else -> result.notImplemented()
        }
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            -1
        }
    }

    private suspend fun connect(): OutputStream? = withContext(Dispatchers.IO) {
        state = "false"
        var out: OutputStream? = null
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && adapter.isEnabled) {
            try {
                val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
                val socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                adapter.cancelDiscovery()
                socket.connect()
                if (socket.isConnected) {
                    out = socket.outputStream
                    state = "true"
                }
            } catch (e: Exception) {
                state = "false"
                Log.d(TAG, "Connect error: ${e.message}")
                out?.close()
            }
        }
        out
    }

    private suspend fun disconnect(): OutputStream? = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        outputStream?.close()
        outputStream = null
        state = "false"
        null
    }

    private fun getLinkedDevices(): List<String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices ?: emptySet()
        return devices.map { "${it.name}#${it.address}" }
    }

    private fun ShowToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    object setBytes {
        val size = arrayOf(
            byteArrayOf(0x1d, 0x21, 0x00),
            byteArrayOf(0x1b, 0x4d, 0x01),
            byteArrayOf(0x1b, 0x4d, 0x00),
            byteArrayOf(0x1d, 0x21, 0x11),
            byteArrayOf(0x1d, 0x21, 0x22),
            byteArrayOf(0x1d, 0x21, 0x33)
        )
    }
}