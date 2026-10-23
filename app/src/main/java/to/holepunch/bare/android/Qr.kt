package to.holepunch.bare.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

// A QR code as a crisp bitmap: each module is drawn as a block of pixels so scaling never blurs it.
fun qrBitmap(text: String, scale: Int = 10): Bitmap {
  val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) // default 4-module quiet zone
  val pixels = IntArray(matrix.width * scale * matrix.height * scale)
  for (y in 0 until matrix.height * scale) for (x in 0 until matrix.width * scale) {
    pixels[y * matrix.width * scale + x] = if (matrix[x / scale, y / scale]) Color.BLACK else Color.WHITE
  }
  return Bitmap.createBitmap(pixels, matrix.width * scale, matrix.height * scale, Bitmap.Config.ARGB_8888)
}
