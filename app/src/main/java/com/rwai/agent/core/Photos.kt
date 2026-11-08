package com.rwai.agent.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Фото, подготовленное к отправке модели: сжатая JPEG-копия в виде data-URL и маленькая миниатюра для чата. */
class AttachedImage(val dataUrl: String, val thumb: Bitmap)

/** Фото, которыми владелец поделился из Галереи («Поделиться → RW AI»); экран ассистента забирает их и очищает список. */
object IncomingShare {
    val uris = MutableStateFlow<List<Uri>>(emptyList())
}

object PhotoEncoder {
    const val MAX_PHOTOS = 4
    private const val MAX_SIDE = 1600
    private const val THUMB_SIDE = 220
    private const val QUALITY = 85

    /**
     * Читает фото по uri, поворачивает по EXIF, уменьшает до [MAX_SIDE] пикселей по длинной стороне
     * и кодирует в JPEG. Метаданные (геопозиция и т.п.) при перекодировании не сохраняются.
     * Возвращает null, если файл не удалось прочитать как изображение.
     */
    suspend fun encode(context: Context, uri: Uri): AttachedImage? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@withContext null
            val rotation = resolver.openInputStream(uri)?.use { readRotation(it) } ?: 0
            var bmp = raw
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0) m.postRotate(rotation.toFloat())
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val ts = THUMB_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val thumb = if (ts < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ts).toInt().coerceAtLeast(1), (bmp.height * ts).toInt().coerceAtLeast(1), true) else bmp
            AttachedImage(url, thumb)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun readRotation(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
