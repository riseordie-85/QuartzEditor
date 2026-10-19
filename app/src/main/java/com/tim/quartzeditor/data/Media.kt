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

/** [compress] null: written by the app itself (TIFF). */
enum class ExportFormat(val label: String, val mime: String, val ext: String, val compress: Bitmap.CompressFormat?, val alpha: Boolean) {
    PNG("PNG", "image/png", "png", Bitmap.CompressFormat.PNG, true),
    JPEG("JPG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, false),
    WEBP("WebP", "image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSLESS, true),
    TIFF("TIFF", "image/tiff", "tif", null, true),
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
            // Only pictures Android can show (not Photoshop files and the like)
            val types = listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/gif", "image/bmp", "image/avif", "image/x-ms-bmp")
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.MIME_TYPE} IN (${types.joinToString { "?" }})")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, types.toTypedArray())
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

    /** Whether [uri] is a TIFF (by its first bytes; Android can't decode TIFFs itself). Reads the file. */
    fun isTiff(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && com.tim.quartzeditor.engine.TiffReader.isTiff(head)
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** A TIFF's picture (its flattened one, for a layered TIFF) as a bitmap no bigger than [maxPx] on its longest side. */
    fun decodeTiff(context: Context, uri: Uri, maxPx: Int): Bitmap {
        val tmp = java.io.File(context.cacheDir, "decode-${System.nanoTime()}.tif")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                ?: throw IOException("Couldn't read that file.")
            val buf = java.io.RandomAccessFile(tmp, "r").use { raf ->
                if (raf.length() > Int.MAX_VALUE) throw IOException("This file is over 2 GB, too big to open here.")
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            return decodeTiff(buf, maxPx)
        } finally {
            tmp.delete()
        }
    }

    /** A TIFF held in [buf] as a bitmap no bigger than [maxPx], turned upright. */
    fun decodeTiff(buf: java.nio.ByteBuffer, maxPx: Int): Bitmap {
        val reader = com.tim.quartzeditor.engine.TiffReader
        val info = try {
            reader.info(buf)
        } catch (e: com.tim.quartzeditor.engine.TiffException) {
            throw IOException(e.message)
        }
        reader.unsupported(info)?.let { throw IOException(it) }
        val k = minOf(1f, maxPx.toFloat() / max(info.width, info.height))
        val w = (info.width * k).roundToInt().coerceAtLeast(1)
        val h = (info.height * k).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val jpeg = com.tim.quartzeditor.engine.PsdImport.androidJpeg
        if (w == info.width && h == info.height) {
            reader.rows(buf, info, jpeg) { y, row -> bmp.setPixels(row, 0, w, 0, y, w, 1) }
        } else {
            val shrink = com.tim.quartzeditor.engine.RowShrinker(info.width, info.height, w, h) { y, row -> bmp.setPixels(row, 0, w, 0, y, w, 1) }
            reader.rows(buf, info, jpeg) { y, row -> shrink.row(y, row) }
            shrink.finish()
        }
        val m = android.graphics.Matrix()
        when (info.orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        if (m.isIdentity) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Decodes an image upright, as editable pixels, no bigger than [maxPx] on its longest side. */
    fun decodeUpright(context: Context, uri: Uri, maxPx: Int): Bitmap {
        if (isTiff(context, uri)) return decodeTiff(context, uri, maxPx)
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

    /** [bitmap] as a TIFF (8-bit RGB, with transparency when it has any). */
    fun writeTiff(bitmap: Bitmap, out: java.io.OutputStream) {
        val w = bitmap.width
        val alpha = bitmap.hasAlpha()
        com.tim.quartzeditor.engine.TiffWriter.write(out, w, bitmap.height, alpha, "Quartz Editor", { y, row -> bitmap.getPixels(row, 0, w, 0, y, w, 1) })
        out.flush()
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
                val c = format.compress
                if (c == null) writeTiff(bitmap, out.buffered(1 shl 16))
                else if (!bitmap.compress(c, if (format == ExportFormat.JPEG) 95 else 100, out)) throw IOException("Couldn't encode the picture")
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
