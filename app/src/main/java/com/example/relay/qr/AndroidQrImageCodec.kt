package com.example.relay.qr

import android.graphics.Bitmap
import com.example.relay.qr.QrTransferCodec.decodeFrame
import com.example.relay.qr.QrTransferCodec.encodeForQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Android image bridge; the shared codec remains responsible for framing and hash checks. */
object AndroidQrImageCodec {
    fun encode(frame: com.example.relay.qr.QrTransferFrame, size: Int = 640): Bitmap {
        require(size in 128..2048)
        val matrix: BitMatrix = MultiFormatWriter().encode(
            encodeForQr(frame).decodeToString(), BarcodeFormat.QR_CODE, size, size,
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt())
            }
        }
    }

    fun decode(bitmap: Bitmap): com.example.relay.qr.QrTransferFrame? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return runCatching {
            val source = RGBLuminanceSource(width, height, pixels)
            decodeFrame(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text.encodeToByteArray())
        }.getOrNull()
    }
}
