package com.auralis

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

internal data class MusicTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long
) {
    val albumArtUri: Uri?
        get() = if (albumId > 0L) Uri.parse("content://media/external/audio/albumart/$albumId") else null
    val durationLabel: String
        get() {
            val total = (durationMs / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(total / 60L, total % 60L)
        }
}

internal object MediaLibraryRepository {
    fun load(context: Context): List<MusicTrack> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val result = ArrayList<MusicTrack>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result.add(MusicTrack(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                    artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                    album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown album",
                    albumId = cursor.getLong(albumIdCol),
                    durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                    dateAddedSeconds = cursor.getLong(addedCol).coerceAtLeast(0L)
                ))
            }
        }
        return result
    }
}

/** Local favourites and user playlists; all metadata stays on the device. */
internal class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("auralis.library", Context.MODE_PRIVATE)

    fun favourites(): Set<Long> = prefs.getStringSet(KEY_FAVOURITES, mutableSetOf<String>())
        .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun isFavourite(id: Long): Boolean = id in favourites()

    fun toggleFavourite(id: Long): Boolean {
        val values = favourites().toMutableSet()
        val nowFavourite = if (values.remove(id)) false else { values.add(id); true }
        prefs.edit().putStringSet(KEY_FAVOURITES, values.map { it.toString() }.toSet()).apply()
        return nowFavourite
    }

    fun playlists(): List<String> {
        val objectValue = playlistObject()
        return buildList {
            val keys = objectValue.keys()
            while (keys.hasNext()) add(keys.next())
        }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return false
        val objectValue = playlistObject()
        if (objectValue.has(clean)) return false
        objectValue.put(clean, JSONArray())
        savePlaylists(objectValue)
        return true
    }

    fun addToPlaylist(name: String, trackId: Long): Boolean {
        val objectValue = playlistObject()
        val array = objectValue.optJSONArray(name) ?: return false
        for (i in 0 until array.length()) if (array.optLong(i, -1L) == trackId) return false
        array.put(trackId)
        savePlaylists(objectValue)
        return true
    }

    fun trackIds(name: String): List<Long> {
        if (name == FAVOURITES_NAME) return favourites().toList()
        val array = playlistObject().optJSONArray(name) ?: return emptyList()
        return buildList { for (i in 0 until array.length()) add(array.optLong(i, -1L)) }.filter { it >= 0L }
    }

    fun removeFromPlaylist(name: String, trackId: Long): Boolean {
        if (name == FAVOURITES_NAME) {
            if (!isFavourite(trackId)) return false
            toggleFavourite(trackId)
            return true
        }
        val objectValue = playlistObject()
        val array = objectValue.optJSONArray(name) ?: return false
        val kept = JSONArray()
        var removed = false
        for (i in 0 until array.length()) {
            val value = array.optLong(i, -1L)
            if (value == trackId) removed = true else kept.put(value)
        }
        if (removed) {
            objectValue.put(name, kept)
            savePlaylists(objectValue)
        }
        return removed
    }

    fun deletePlaylist(name: String): Boolean {
        val objectValue = playlistObject()
        if (!objectValue.has(name)) return false
        objectValue.remove(name)
        savePlaylists(objectValue)
        return true
    }

    private fun playlistObject(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_PLAYLISTS, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun savePlaylists(value: JSONObject) {
        prefs.edit().putString(KEY_PLAYLISTS, value.toString()).apply()
    }

    companion object {
        const val FAVOURITES_NAME = "Favourites"
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_PLAYLISTS = "playlists"
    }
}

/** Most-recently-played track URIs, newest first. */
internal object PlayHistory {
    private const val PREFS = "auralis.history"
    private const val KEY = "recent"
    private const val LIMIT = 40

    fun record(context: Context, uri: Uri) {
        val value = uri.toString()
        val list = recent(context).toMutableList()
        list.remove(value)
        list.add(0, value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(list.take(LIMIT)).toString()).apply()
    }

    fun recent(context: Context): List<String> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())
}
