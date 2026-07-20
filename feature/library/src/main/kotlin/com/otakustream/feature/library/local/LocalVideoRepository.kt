package com.otakustream.feature.library.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LocalVideo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedEpochS: Long,
    val bucketName: String,
    // Filesystem path (MediaStore DATA — deprecated but still populated). Carried read-only:
    // used later for best-effort sidecar-subtitle detection, never written to.
    val dataPath: String?,
)

// Lists the device's videos straight from MediaStore — queried live on demand, no local cache.
@Singleton
class LocalVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun loadVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
        )
        val videos = mutableListOf<LocalVideo>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    videos += LocalVideo(
                        id = id,
                        // Stable per item across sessions, so URL-keyed resume-from-position
                        // works with no extra bookkeeping.
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(nameCol) ?: "Video $id",
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedEpochS = cursor.getLong(dateCol),
                        bucketName = cursor.getString(bucketCol) ?: "",
                        dataPath = cursor.getString(dataCol),
                    )
                }
            }
        }
        videos
    }
}
