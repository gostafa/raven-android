package com.gostafa.raven.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class StoredMedia(val relativePath: String, val mimeType: String, val byteSize: Long, val originalName: String? = null)

class MediaStorage @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun import(uri: Uri, expectedType: String): StoredMedia {
        var importedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val mimeType = context.contentResolver.getType(uri) ?: expectedType
                require(mimeType.startsWith(expectedType.substringBefore('/') + "/")) { "Unexpected media type" }
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    ?: when (expectedType) {
                        "image/*" -> "img"
                        "audio/*" -> "audio"
                        else -> "video"
                    }
                val directory = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
                val name = "${UUID.randomUUID()}.$extension"
                val target = File(directory, name)
                importedFile = target
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open selected media")
                input.use { source ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(target.length() > 0) { "Selected file is empty" }
                val originalName = if (uri.scheme == "file") uri.lastPathSegment else runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
                StoredMedia("$MEDIA_DIRECTORY/$name", mimeType, target.length(), originalName?.takeIf { it.isNotBlank() })
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { importedFile?.delete() }
            throw error
        }
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
