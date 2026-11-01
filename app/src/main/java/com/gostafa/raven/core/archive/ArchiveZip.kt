package com.gostafa.raven.core.archive

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlin.coroutines.coroutineContext

internal const val MAX_MANIFEST = 64L shl 20
internal const val MAX_MEDIA = 8L shl 30
internal const val MAX_TOTAL = 32L shl 30
internal const val MAX_ENTRIES = 100_000

internal val mediaTypes = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "avif" to "image/avif",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "ogg" to "audio/ogg",
    "oga" to "audio/ogg", "flac" to "audio/flac", "mp4" to "video/mp4", "m4v" to "video/mp4",
    "webm" to "video/webm", "ogv" to "video/ogg", "mov" to "video/quicktime",
)
internal fun mediaName(name: String) = name.startsWith("media/") && name.substring(6).let {
    it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c in "/\\:\u0000" }
}

internal object ArchiveZip {
    fun validate(document: ArchiveDocument): List<ArchiveItem> {
        require(document.version == 1 && document.folders.isNotEmpty()) { "Unsupported or empty Raven archive" }
        require(java.time.Instant.parse(document.exportedAt) != java.time.Instant.parse(ZERO_TIME)) { "Missing export time" }
        val folderIds = mutableSetOf<String>()
        val media = mutableListOf<ArchiveItem>()
        document.folders.forEach { folder ->
            require(folder.folder.id.isNotEmpty() && folderIds.add(folder.folder.id) && folder.folder.title.isNotBlank()) { "Invalid folder" }
            val cardIds = mutableSetOf<String>()
            folder.cards.forEach { card ->
                require(card.id.isNotEmpty() && cardIds.add(card.id) && card.folderId == folder.folder.id && card.createdAt > 0 && card.items.isNotEmpty()) { "Invalid card" }
                val itemIds = mutableSetOf<String>()
                card.items.sortedBy { it.position }.forEachIndexed { index, item ->
                    require(item.id.isNotEmpty() && itemIds.add(item.id) && item.cardId == card.id && item.folderId == card.folderId && item.position == index) { "Invalid content ownership or order" }
                    validateSchedule(item.schedule)
                    require(item.reviews.isNotEmpty()) { "Missing review history" }
                    item.reviews.forEach { review ->
                        val log = review.log
                        require(log.state in -128..127) { "Invalid review state" }
                        require(log.rating in 1..4 && java.time.Instant.parse(log.review) != java.time.Instant.parse(ZERO_TIME) && java.time.Instant.parse(log.due) != java.time.Instant.parse(ZERO_TIME)) { "Invalid review log" }
                    }
                    if (item.kind == "text") {
                        require(item.text.isNotBlank() && item.relativePath.isEmpty() && item.mimeType.isEmpty() && item.originalName.isEmpty() && item.byteSize == 0L) { "Invalid text content" }
                    } else {
                        val ext = item.relativePath.substringAfterLast('.', "")
                        require(item.id.matches(Regex("[a-f0-9]{32}")) && item.kind in listOf("image", "audio", "video") && item.text.isEmpty() && item.originalName.isNotEmpty() && item.byteSize in 1..MAX_MEDIA &&
                            mediaName(item.relativePath) && item.relativePath == "media/${item.id}.$ext" && mediaTypes[ext] == item.mimeType && item.mimeType.startsWith(item.kind + "/")) { "Invalid media metadata" }
                        media += item
                    }
                }
            }
        }
        require(media.size < MAX_ENTRIES && media.map { it.relativePath }.distinct().size == media.size) { "Duplicate media references" }
        var total = 0L
        media.forEach { total += it.byteSize; require(total <= MAX_TOTAL) { "Archive is too large" } }
        return media
    }

    private fun validateSchedule(schedule: PortableSchedule) {
        require(schedule.state in 0..3 && schedule.stability.isFinite() && schedule.difficulty.isFinite()) { "Invalid schedule" }
        require(schedule.state == 0 || (schedule.stability >= 0.001 && schedule.difficulty >= 1.0)) { "Invalid learned schedule" }
        java.time.Instant.parse(schedule.due).toEpochMilli()
        java.time.Instant.parse(schedule.lastReview).toEpochMilli()
        require(java.time.Instant.parse(schedule.due) != java.time.Instant.parse(ZERO_TIME) && java.time.Instant.parse(schedule.lastReview) != java.time.Instant.parse(ZERO_TIME)) { "Missing schedule" }
    }

    suspend fun read(file: File, consume: suspend (ArchiveDocument, ZipFile) -> ArchiveResult): ArchiveResult {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= MAX_ENTRIES && entries.map { it.name }.distinct().size == entries.size) { "Invalid ZIP entries" }
            entries.forEach { entry ->
                require((entry.name == "media/" && entry.isDirectory) || (!entry.isDirectory && (entry.name == "cards.json" || mediaName(entry.name)))) { "Unsafe archive entry" }
            }
            validateZipPermissions(file, entries.size)
            val manifest = zip.getEntry("cards.json") ?: error("Archive has no cards.json")
            require(manifest.size in 1..MAX_MANIFEST) { "Manifest is too large" }
            val bytes = java.io.ByteArrayOutputStream()
            zip.getInputStream(manifest).use { copyChecked(it, bytes, manifest.size, manifest.crc) }
            val document = archiveJson.decodeFromString<ArchiveDocument>(bytes.toString("UTF-8"))
            val references = validate(document)
            require(entries.size == references.size + 1 + if (zip.getEntry("media/") != null) 1 else 0) { "Unexpected archive files" }
            references.forEach { item -> require(zip.getEntry(item.relativePath)?.size == item.byteSize) { "Missing or truncated media" } }
            return consume(document, zip)
        }
    }

    suspend fun write(file: File, document: ArchiveDocument, open: (ArchiveItem) -> InputStream) {
        val media = validate(document)
        val manifest = archiveJson.encodeToString(document).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST) { "Manifest is too large" }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("cards.json")); zip.write(manifest); zip.closeEntry()
            media.forEach { item ->
                zip.putNextEntry(ZipEntry(item.relativePath))
                open(item).use { copyChecked(it, zip, item.byteSize) }
                zip.closeEntry()
            }
        }
    }
}

internal suspend fun copyChecked(input: InputStream, output: OutputStream, size: Long, crc: Long = -1) {
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    val checksum = CRC32()
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        require(count <= size) { "Archive data exceeds declared size" }
        checksum.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    require(count == size && (crc == -1L || checksum.value == crc)) { "Corrupt or truncated archive data" }
}
