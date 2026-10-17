package com.tim.quartzeditor.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo from the phone's gallery. */
data class GalleryPhoto(val id: Long, val uri: Uri, val name: String)

enum class ExportFormat(val label: String, val mime: String, val ext: String, val compress: Bitmap.CompressFormat, val alpha: Boolean) {
    PNG("PNG", "image/png", "png", Bitmap.CompressFormat.PNG, true),
    JPEG("JPG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, false),
    WEBP("WebP", "image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSLESS, true),
}

object Media {
    const val FOLDER = "Quartz Editor"
    val FOLDER_LABEL = "${Environment.DIRECTORY_PICTURES}/$FOLDER"

    /** Newest photos first. Needs photo access (or whatever the user chose to share). */
    suspend fun recentPhotos(context: Context, limit: Int = 120): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val out = ArrayList<GalleryPhoto>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val args = android.os.Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        try {
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(GalleryPhoto(id, ContentUris.withAppendedId(collection, id), c.getString(nameCol) ?: "Photo"))
                }
            }
        } catch (_: SecurityException) {
        }
        out
    }

    /** File name of a picked/shared image, without the extension. */
    fun displayName(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return (name ?: uri.lastPathSegment ?: "Image").substringBeforeLast('.').ifBlank { "Image" }
    }

    /** The file's name with its extension (for telling what kind of file it is). */
    fun displayNameWithExtension(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment.orEmpty()

    /** Decodes an image upright, as editable pixels, no bigger than [maxPx] on its longest side. */
    fun decodeUpright(context: Context, uri: Uri, maxPx: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxPx) {
                val s = maxPx.toFloat() / longest
                decoder.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
        // Wide-gamut and 16-bit photos become ordinary 8-bit sRGB
        return if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
    }

    /** Saves a finished image to Pictures/Quartz Editor. Returns its address. */
    suspend fun saveImage(context: Context, bitmap: Bitmap, name: String, format: ExportFormat): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.${format.ext}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER_LABEL)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Android didn't allow a new picture")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(format.compress, if (format == ExportFormat.JPEG) 95 else 100, out)) throw IOException("Couldn't encode the picture")
            } ?: throw IOException("Couldn't write the picture")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
