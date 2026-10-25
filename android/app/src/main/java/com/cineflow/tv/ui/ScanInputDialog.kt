package com.cineflow.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.cineflow.tv.Net
import com.cineflow.tv.R
import com.cineflow.tv.WwwServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

/**
 * Scan-to-type, native edition: the TV shows a QR code; the phone opens a
 * one-time LAN page served by the app's own WwwServer and whatever gets
 * typed there is delivered into the target field. Same plumbing as the
 * WebView-era feature, driven from a dialog instead of the JS bridge.
 */
class ScanInputDialog(
    context: Context,
    private val title: String,
    private val onText: (String) -> Unit
) : Dialog(context) {

    private var server: WwwServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_scan)
        window?.setLayout(context.dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
        findViewById<TextView>(R.id.scanTitle).text = title
        val urlText = findViewById<TextView>(R.id.scanUrl)
        val qrImage = findViewById<ImageView>(R.id.scanQr)
        findViewById<TextView>(R.id.scanCancelBtn).setOnClickListener { dismiss() }

        val ip = Net.lanIpAddress()
        if (ip == null) {
            urlText.text = "电视未连接到局域网，无法扫码输入"
            return
        }
        val token = newToken()
        val srv = WwwServer(context, "")
        srv.inputTitle = title
        srv.inputToken = token
        srv.onInputSubmitted = { text ->
            // Delivered on the HTTP server thread; hop to the UI thread.
            qrImage.post {
                dismiss()
                if (text.isNotBlank()) onText(text)
            }
        }
        srv.start()
        server = srv
        val url = "http://$ip:${srv.listeningPort}/input?t=$token"
        urlText.text = url
        qrImage.setImageBitmap(makeQr(url))
    }

    override fun dismiss() {
        stopServer()
        super.dismiss()
    }

    override fun onStop() {
        stopServer()
        super.onStop()
    }

    private fun stopServer() {
        val srv = server ?: return
        server = null
        srv.onInputSubmitted = null
        srv.inputToken = null
        try {
            srv.stop()
        } catch (e: Exception) {
            // already stopped
        }
    }

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..18).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun makeQr(content: String): Bitmap {
        val size = 480
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
