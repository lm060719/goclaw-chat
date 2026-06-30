package xyz.limo060719.goclaw.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.limo060719.goclaw.domain.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtil {
    // Keep uploads small: the backend vision provider rejects large images (HTTP 413).
    private const val MAX_DIM = 1024
    private const val JPEG_QUALITY = 80
    private const val AVATAR_DIM = 256

    /**
     * Decodes [uri], downscales to a small avatar, and writes it to internal storage.
     * Returns the absolute file path, or null on failure.
     */
    suspend fun saveAvatar(context: Context, uri: Uri, name: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = loadBitmap(context, uri) ?: return@runCatching null
                val scaled = downscale(bitmap, AVATAR_DIM)
                val dir = File(context.filesDir, "avatars").apply { mkdirs() }
                // Remove prior avatar(s) for this role; unique filename busts the image cache.
                dir.listFiles { f -> f.name.startsWith("$name-") }?.forEach { it.delete() }
                val file = File(dir, "$name-${System.currentTimeMillis()}.jpg")
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            }.getOrNull()
        }

    suspend fun toAttachment(context: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = loadBitmap(context, uri) ?: return@runCatching null
            val scaled = downscale(bitmap)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            Attachment(mimeType = "image/jpeg", base64 = base64, previewUri = uri.toString())
        }.getOrNull()
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { d, _, _ -> d.isMutableRequired = false }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }

    private fun downscale(bmp: Bitmap, maxDim: Int = MAX_DIM): Bitmap {
        val w = bmp.width; val h = bmp.height
        val largest = maxOf(w, h)
        if (largest <= maxDim) return bmp
        val ratio = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }
}
