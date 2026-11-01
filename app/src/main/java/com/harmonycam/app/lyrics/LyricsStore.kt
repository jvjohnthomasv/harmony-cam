package com.harmonycam.app.lyrics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Song(val id: String, val title: String, val lyrics: String) {
    val lines: List<String> get() = lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

/** Saved songs (title + pasted lyrics) as a small JSON file in app storage. */
class LyricsStore(context: Context) {
    private val file = File(context.filesDir, "songs.json")

    fun load(): List<Song> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Song(o.getString("id"), o.optString("title"), o.optString("lyrics"))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun save(songs: List<Song>) {
        val arr = JSONArray()
        for (s in songs) arr.put(JSONObject().put("id", s.id).put("title", s.title).put("lyrics", s.lyrics))
        val tmp = File(file.parentFile, "songs.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun upsert(songs: List<Song>, song: Song): List<Song> {
        val list = songs.toMutableList()
        val i = list.indexOfFirst { it.id == song.id }
        if (i >= 0) list[i] = song else list.add(0, song)
        save(list)
        return list
    }

    fun delete(songs: List<Song>, id: String): List<Song> {
        val list = songs.filter { it.id != id }
        save(list)
        return list
    }

    companion object {
        fun newId() = UUID.randomUUID().toString()
    }
}
