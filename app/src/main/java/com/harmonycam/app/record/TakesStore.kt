package com.harmonycam.app.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class Take(val file: File, val number: Int, val createdMs: Long, val durationSec: Int, val saved: Boolean) {
    val name: String get() = file.nameWithoutExtension
}

/** Every recorded take is kept in app storage until you delete it; save the good ones to the Gallery. */
class TakesStore(private val context: Context) {
    val dir = File(context.filesDir, "takes").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("takes", Context.MODE_PRIVATE)

    fun list(): List<Take> = (dir.listFiles { f -> f.extension == "mp4" } ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .map { f ->
            Take(
                file = f,
                number = f.nameWithoutExtension.substringAfter("Take_").substringBefore('_').toIntOrNull() ?: 0,
                createdMs = f.lastModified(),
                durationSec = prefs.getInt("dur_${f.name}", -1).let { if (it >= 0) it else durationOf(f) },
                saved = prefs.getBoolean("saved_${f.name}", false),
            )
        }

    fun nextNumber(): Int = prefs.getInt("counter", 0) + 1

    fun newFile(): File {
        val n = nextNumber()
        prefs.edit().putInt("counter", n).apply()
        return File(dir, "Take_${n}_${System.currentTimeMillis()}.mp4")
    }

    private fun durationOf(f: File): Int = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        val s = (ms / 1000).toInt()
        prefs.edit().putInt("dur_${f.name}", s).apply()
        s
    } catch (_: Exception) { 0 }

    fun delete(take: Take) {
        take.file.delete()
        prefs.edit().remove("saved_${take.file.name}").remove("dur_${take.file.name}").apply()
    }

    fun saveToGallery(take: Take): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HarmonyCam_${take.name}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HarmonyCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { os -> take.file.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            prefs.edit().putBoolean("saved_${take.file.name}", true).apply()
        }
        uri
    } catch (e: Exception) {
        Log.e("TakesStore", "gallery save failed", e); null
    }
}
