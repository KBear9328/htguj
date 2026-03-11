package com.example.mangareader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object ZipExtractor {

    private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    suspend fun extractImagesFromZip(
        context: Context,
        zipUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Pair<String, Bitmap>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, Bitmap>>()
        val entries = mutableListOf<Pair<String, ByteArray>>()

        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in SUPPORTED_EXTENSIONS) {
                        entries.add(Pair(name, zip.readBytes()))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val sorted = entries.sortedWith(compareBy { naturalSortKey(it.first) })

        sorted.forEachIndexed { index, (name, bytes) ->
            onProgress(index + 1, sorted.size)
            decodeSampledBitmap(bytes, 1920, 2880)?.let { results.add(Pair(name, it)) }
        }
        results
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun naturalSortKey(name: String): String {
        val baseName = name.substringAfterLast('/').substringAfterLast('\\')
        return baseName.replace(Regex("(\\d+)")) { m -> m.value.padStart(10, '0') }
    }
}
