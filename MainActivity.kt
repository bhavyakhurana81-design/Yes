package com.greenpad.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val bt: BluetoothAdapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    // Report 1 = keyboard, 2 = consumer (volume/home/play), 3 = mouse (right joystick)
    private val desc = intArrayOf(
        0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
        0x95,0x01,0x75,0x08,0x81,0x01,0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0,
        0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x02,0x15,0x00,0x26,0xFF,0x03,0x19,0x00,0x2A,0xFF,0x03,0x75,0x10,0x95,0x01,0x81,0x00,0xC0,
        0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x03,0x09,0x01,0xA1,0x00,0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,
        0x95,0x01,0x75,0x05,0x81,0x03,0x05,0x01,0x09,0x30,0x09,0x31,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x02,0x81,0x06,0xC0,0xC0
    ).map { it.toByte() }.toByteArray()

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            if (s == BluetoothProfile.STATE_CONNECTED) host = d
            js("onBt($s,'${d.name}')")
        }
    }

    private fun js(c: String) = runOnUiThread { web.evaluateJavascript(c, null) }
    private fun send(id: Int, d: ByteArray) { val h = host ?: return; hid?.sendReport(h, id, d) }

    inner class Bridge {
        @JavascriptInterface fun devices(): String =
            JSONArray(bt.bondedDevices.map { JSONObject().put("n", it.name).put("a", it.address) }).toString()

        @JavascriptInterface fun register() {
            bt.getProfileProxy(this@MainActivity, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val h = proxy as BluetoothHidDevice; hid = h
                    val sdp = BluetoothHidDeviceAppSdpSettings("GreenPad", "Phone controller", "GreenPad",
                        BluetoothHidDevice.SUBCLASS1_COMBO, desc)
                    h.registerApp(sdp, null, null, Executor { it.run() }, cb)
                    js("onBt(-1,'registered')")
                }
                override fun onServiceDisconnected(p: Int) { hid = null }
            }, BluetoothProfile.HID_DEVICE)
        }

        @JavascriptInterface fun connect(addr: String) { hid?.connect(bt.getRemoteDevice(addr)) }

        @JavascriptInterface fun key(code: Int) {
            send(1, byteArrayOf(0, 0, code.toByte(), 0, 0, 0, 0, 0)); send(1, ByteArray(8))
        }
        @JavascriptInterface fun consumer(u: Int) {
            send(2, byteArrayOf((u and 0xFF).toByte(), (u shr 8).toByte())); send(2, ByteArray(2))
        }
        @JavascriptInterface fun mouse(b: Int, dx: Int, dy: Int) {
            send(3, byteArrayOf(b.toByte(), dx.toByte(), dy.toByte()))
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }
}
