package com.whisper.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Génération de QR codes pour le partage de clé publique.
 *
 * Utilisé à l'onboarding (afficher sa clé) et en Phase 5 (échange de contacts).
 * Format encodé : "whospair:<X25519 hex><Ed25519 hex><onion>" — voir ContactKeyParser.toQrPayload().
 */
object QrCodeGenerator {

    private const val DEFAULT_SIZE = 512

    /**
     * Encode du texte en bitmap QR.
     *
     * @return null si l'encodage échoue — l'appelant affiche alors la clé
     *         en texte, qui reste copiable. Un QR absent ne doit pas bloquer
     *         l'onboarding.
     */
    fun generate(
        content: String,
        size: Int = DEFAULT_SIZE,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE
    ): Bitmap? {
        if (content.isBlank()) return null

        return try {
            val hints = mapOf(
                // Correction haute : le code reste lisible même photographié
                // de travers ou sur un écran sale.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )

            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
            )

            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix[x, y]) foreground else background
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Variante directement consommable par Compose.
     */
    fun generateImageBitmap(content: String, size: Int = DEFAULT_SIZE): ImageBitmap? =
        generate(content, size)?.asImageBitmap()
}
