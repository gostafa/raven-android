package com.gostafa.raven.core.archive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.decodeTags
import com.gostafa.raven.core.model.normalizeTags
import com.gostafa.raven.core.reminders.ReminderInvalidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Singleton
class FolderArchiveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RavenDatabase,
    private val mediaStorage: MediaStorage,
    private val reminders: ReminderInvalidator,
) {
    private val mutex = Mutex()

    suspend fun exportFolders(folderIds: List<String>, destination: Uri): ArchiveResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(folderIds.isNotEmpty() && folderIds.size <= MAX_ENTRIES && folderIds.all { it.isNotEmpty() }) { "Select at least one folder" }
            val staging = stagingDirectory()
            try {
                // Copy media while the read transaction pins its references against concurrent deletes/edits.
                val document = database.withTransaction {
                    var mediaCount = 0
                    var expandedSize = 0L
                    ArchiveDocument(exportedAt = timestamp(System.currentTimeMillis()), folders = folderIds.distinct().map { folderId ->
                        val folder = database.folderDao().get(folderId) ?: error("Folder is no longer available")
                        ArchiveFolder(ArchiveFolderMetadata(folder.id, folder.name, folder.description, decodeTags(folder.tagsJson)),
                            database.cardDao().allInFolder(folderId).map { card ->
                                val items = database.contentPartDao().forCard(card.id).mapIndexed { position, part ->
                                    val id = part.id.replace("-", "")
                                    val compatibility = database.archiveStateDao().get(part.id)
                                    val schedule = compatibility?.let { archiveJson.decodeFromString<PortableSchedule>(it.scheduleJson) }
                                        ?: part.fsrsState().let { it.copy(lastReviewAt = it.lastReviewAt ?: part.createdAt).portable() }
                                    val reviews = compatibility?.let { archiveJson.decodeFromString<List<PortableReview>>(it.reviewsJson) }
                                        ?: listOf(PortableReview(PortableLog(timestamp(part.createdAt), timestamp(part.createdAt), 1), automatic = true)) + database.reviewHistoryDao().forPart(part.id).map { history ->
                                            portableReview(archiveJson.decodeFromString(history.stateBeforeJson), history.rating, history.reviewedAt)
                                        }
                                    var path = ""
                                    var size = 0L
                                    var mime = ""
                                    if (part.type != "TEXT") {
                                        val source = mediaStorage.file(requireNotNull(part.mediaPath))
                                        val extension = source.extension.lowercase()
                                        mime = mediaTypes[extension] ?: error("Attachment format is not supported by Raven: $extension")
                                        path = "media/$id.$extension"
                                        size = source.length()
                                        require(size in 1..MAX_MEDIA) { "Attachment is missing or too large" }
                                        mediaCount++
                                        expandedSize += size
                                        require(mediaCount < MAX_ENTRIES && expandedSize <= MAX_TOTAL) { "Archive is too large" }
                                        inspectMedia(source, mime)
                                        val target = File(staging, path).apply { parentFile?.mkdirs() }
                                        source.inputStream().use { input -> target.outputStream().use { copyChecked(input, it, size) } }
                                    }
                                    ArchiveItem(id, folder.id, card.id, part.type.lowercase(), position, schedule, reviews,
                                        text = if (part.type == "TEXT") part.text else "", relativePath = path, mimeType = mime,
                                        originalName = if (path.isEmpty()) "" else part.originalName?.takeIf { it.isNotBlank() } ?: File(requireNotNull(part.mediaPath)).name,
                                        byteSize = size, hint = !part.rememberThis)
                                }
                                ArchiveCard(card.id, folder.id, card.createdAt, items,
                                    dueAt = items.filterNot { it.hint }.minOfOrNull { java.time.Instant.parse(it.schedule.due).toEpochMilli() } ?: 0,
                                    lastReviewed = items.filterNot { it.hint }.flatMap { it.reviews }.filterNot { it.automatic }.maxOfOrNull { java.time.Instant.parse(it.log.review).toEpochMilli() } ?: 0)
                            })
                    })
                }
                val archive = File(staging, "export.zip")
                ArchiveZip.write(archive, document) { File(staging, it.relativePath).inputStream() }
                try {
                    val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("Unable to open save location")
                    output.use { target -> archive.inputStream().use { copyChecked(it, target, archive.length()) } }
                } catch (failure: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                    throw failure
                }
                document.result()
            } finally { staging.deleteRecursively() }
        }
    }

    suspend fun importFolders(source: Uri, mode: ImportMode): ArchiveResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val staging = stagingDirectory()
            val installed = mutableListOf<File>()
            var committed = false
            try {
                val archive = File(staging, "import.zip")
                val input = context.contentResolver.openInputStream(source) ?: error("Unable to open selected archive")
                input.use { stream -> archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                ArchiveZip.read(archive) { document, zip ->
                    val now = System.currentTimeMillis()
                    val copies = document.folders.map { folder ->
                        val folderId = newId()
                        folder.copy(folder = folder.folder.copy(id = folderId), cards = folder.cards.map { card ->
                            val cardId = newId()
                            card.copy(id = cardId, folderId = folderId, items = card.items.sortedBy { it.position }.map { item ->
                                val id = newId()
                                var path = ""
                                if (item.relativePath.isNotEmpty()) {
                                    path = "media/$id.${item.relativePath.substringAfterLast('.')}"
                                    val staged = File(staging, id)
                                    val entry = zip.getEntry(item.relativePath)
                                    zip.getInputStream(entry).use { stream -> staged.outputStream().use { copyChecked(stream, it, item.byteSize, entry.crc) } }
                                    inspectMedia(staged, item.mimeType)
                                    val target = mediaStorage.file(path).apply { parentFile?.mkdirs() }
                                    installed += target
                                    check(staged.renameTo(target)) { "Unable to store imported media" }
                                }
                                item.copy(id = id, cardId = cardId, folderId = folderId, relativePath = path, lastNotifiedDue = 0,
                                    schedule = if (mode == ImportMode.FRESH) freshSchedule(now) else item.schedule,
                                    reviews = if (mode == ImportMode.FRESH) freshReviews(now) else item.reviews)
                            })
                        })
                    }
                    // Do not allow cancellation between a successful commit and setting the cleanup guard.
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        database.withTransaction {
                            copies.forEach { folder ->
                                database.folderDao().upsert(FolderEntity(folder.folder.id, folder.folder.title.trim(), folder.folder.description.trim(), archiveJson.encodeToString(normalizeTags(folder.folder.tags)), now, now))
                                folder.cards.forEach { card ->
                                    database.cardDao().upsert(CardEntity(card.id, card.folderId, card.createdAt, now))
                                    card.items.forEach { item ->
                                        val state = item.schedule.androidState()
                                        val manual = item.reviews.filterNot { it.automatic }
                                        database.contentPartDao().upsert(ContentPartEntity(item.id, card.id, item.kind.uppercase(), item.text,
                                            item.relativePath.takeIf { it.isNotEmpty() }, item.mimeType.takeIf { it.isNotEmpty() }, item.position, !item.hint,
                                            archiveJson.encodeToString(state), dueAt = state.dueAt, reviewCount = manual.size, createdAt = card.createdAt, updatedAt = now,
                                            originalName = item.originalName.takeIf { it.isNotEmpty() }))
                                        database.archiveStateDao().upsert(ArchiveStateEntity(item.id, archiveJson.encodeToString(item.schedule), archiveJson.encodeToString(item.reviews)))
                                        manual.forEach { review ->
                                            val at = java.time.Instant.parse(review.log.review).toEpochMilli()
                                            database.reviewHistoryDao().insert(ReviewHistoryEntity(newId(), item.id, review.log.rating, at,
                                                java.time.Instant.parse(review.log.due).toEpochMilli(), state.dueAt, archiveJson.encodeToString(state), archiveJson.encodeToString(state)))
                                        }
                                        if (item.relativePath.isNotEmpty()) database.mediaDao().upsert(MediaEntity(newId(), item.relativePath, item.mimeType, item.byteSize, now))
                                    }
                                }
                            }
                        }
                        committed = true
                        runCatching { reminders.changed() }.onFailure { android.util.Log.w("FolderArchiveService", "Could not refresh reminders after import", it) }
                    }
                    document.result(copies.map { it.folder.id })
                }
            } finally {
                if (!committed) installed.forEach { it.delete() }
                staging.deleteRecursively()
            }
        }
    }

    private fun stagingDirectory() = File(context.cacheDir, "archive-${newId()}").apply { check(mkdirs()) { "Unable to stage archive" } }
}

private fun newId() = UUID.randomUUID().toString().replace("-", "")
private fun ArchiveDocument.result(ids: List<String> = emptyList()) = ArchiveResult(folders.size, folders.sumOf { it.cards.size }, folders.sumOf { it.cards.sumOf { card -> card.items.count { it.relativePath.isNotEmpty() } } }, ids)
