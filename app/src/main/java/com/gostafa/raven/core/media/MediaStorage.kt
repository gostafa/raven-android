package com.gostafa.raven.core.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredMedia(val relativePath: String, val mimeType: String, val byteSize: Long)

class MediaStorage @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun import(uri: Uri, expectedType: String): StoredMedia = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: expectedType
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: when (expectedType) {
                "image/*" -> "img"
                "audio/*" -> "audio"
                else -> "video"
            }
        val directory = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.$extension"
        val target = File(directory, name)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open selected media")
        input.use { source -> FileOutputStream(target).use { output -> source.copyTo(output) } }
        StoredMedia("$MEDIA_DIRECTORY/$name", mimeType, target.length())
    }

    fun file(relativePath: String): File = File(context.filesDir, relativePath)

    fun delete(relativePath: String) {
        val base = context.filesDir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        if (target.toPath().startsWith(base.toPath())) target.delete()
    }

    private companion object {
        const val MEDIA_DIRECTORY = "media"
    }
}
