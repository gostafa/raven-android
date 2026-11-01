package com.gostafa.raven.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchiveZipTest {
    private val fixture = File("src/androidTest/assets/desktop-folders.zip")
    private val output = File("build/archive-compat").apply { mkdirs() }

    @Test fun desktopArchiveRoundTripsWithoutLosingHistoryOrMedia() = runBlocking {
        ArchiveZip.read(fixture) { original, zip ->
            val copy = original.copy(folders = original.folders.map { folder ->
                folder.copy(cards = folder.cards.map { card -> card.copy(items = card.items.map { it.copy(lastNotifiedDue = 0) }) })
            })
            val destination = File(output, "android-roundtrip.zip")
            ArchiveZip.write(destination, copy) { zip.getInputStream(zip.getEntry(it.relativePath)) }
            ArchiveZip.read(destination) { returned, returnedZip ->
                assertEquals(copy, returned)
                ArchiveZip.validate(copy).forEach { media ->
                    assertArrayEquals(zip.getInputStream(zip.getEntry(media.relativePath)).use { it.readBytes() }, returnedZip.getInputStream(returnedZip.getEntry(media.relativePath)).use { it.readBytes() })
                }
                returned.summary()
            }
        }
        Unit
    }

    @Test fun nativeAndroidSchedulesProducePortableArchives() = runBlocking {
        val now = 1700000000000L
        val initial = com.gostafa.raven.core.model.FsrsState(dueAt = now, lastReviewAt = now)
        val scheduler = com.gostafa.raven.core.scheduler.Fsrs6Scheduler()
        val after = scheduler.schedule(initial, com.gostafa.raven.core.model.Rating.GOOD, now)
        val document = ArchiveDocument(exportedAt = timestamp(now), folders = listOf(
            ArchiveFolder(ArchiveFolderMetadata("native", "Native Android", "", listOf("study")), listOf(
                ArchiveCard("card", "native", now, listOf(
                    ArchiveItem("a".repeat(32), "native", "card", "text", 0, after.portable(),
                        freshReviews(now) + portableReview(initial, 3, now), text = "مرحبا 🌍"),
                    ArchiveItem("b".repeat(32), "native", "card", "text", 1, initial.portable(), freshReviews(now), text = "Hint", hint = true),
                ), dueAt = after.dueAt, lastReviewed = now),
            )), ArchiveFolder(ArchiveFolderMetadata("empty", "Empty"), emptyList()),
        ))
        ArchiveZip.write(File(output, "android-native.zip"), document) { error("No media") }
    }

    @Test fun preservesPreciseTimestampsAndUnsignedCounters() = runBlocking {
        ArchiveZip.read(fixture) { source, zip ->
            val first = source.folders.first().cards.first().items.first()
            val precise = first.schedule.copy(due = "2026-01-02T03:04:05.123456789Z", reps = ULong.MAX_VALUE)
            val encoded = archiveJson.encodeToString(PortableSchedule.serializer(), precise)
            assertEquals(precise, archiveJson.decodeFromString<PortableSchedule>(encoded))
            assertEquals(Int.MAX_VALUE, precise.androidState().repetitions)
            assertEquals(123, (precise.androidState().dueAt % 1000).toInt())
            val transferable = precise.copy(reps = 9_007_199_254_740_993uL, lapses = 4_294_967_296uL, remainingSteps = 5_000_000_000L)
            val document = source.copy(folders = source.folders.map { folder -> folder.copy(cards = folder.cards.map { card -> card.copy(items = card.items.map {
                if (it.id == first.id) it.copy(schedule = transferable) else it
            }).let { updated -> updated.copy(dueAt = updated.items.filterNot { it.hint }.minOfOrNull { java.time.Instant.parse(it.schedule.due).toEpochMilli() } ?: 0) } }) })
            ArchiveZip.write(File(output, "android-precision.zip"), document) { zip.getInputStream(zip.getEntry(it.relativePath)) }
            source.summary()
        }
        Unit
    }

    @Test fun rejectsUnexpectedFilesAndUnsafePaths() = runBlocking {
        listOf("../escape", "media/../escape", "media\\escape", "/absolute", "extra.txt").forEach { name ->
            val file = File(output, "invalid.zip")
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(1); zip.closeEntry() }
            try { ArchiveZip.read(file) { doc, _ -> doc.summary() }; fail("Accepted $name") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsUnknownFieldsOwnershipInvalidSchedulesAndMissingMedia() = runBlocking {
        ArchiveZip.read(fixture) { document, _ ->
            fun changed(transform: (ArchiveItem) -> ArchiveItem) = document.copy(folders = document.folders.map { folder -> folder.copy(cards = folder.cards.map { card -> card.copy(items = card.items.map(transform)) }) })
            listOf(changed { it.copy(cardId = "wrong") }, changed { it.copy(position = 100) }, changed { it.copy(schedule = it.schedule.copy(state = 8)) }, changed { it.copy(reviews = emptyList()) }).forEach { invalid ->
                try { ArchiveZip.validate(invalid); fail("Accepted invalid metadata") } catch (_: IllegalArgumentException) { }
            }
            try { archiveJson.decodeFromString<ArchiveDocument>("""{"version":1,"exported_at":"2026-01-01T00:00:00Z","folders":[],"unknown":1}"""); fail("Accepted unknown field") }
            catch (_: kotlinx.serialization.SerializationException) { }
            val missing = File(output, "missing.zip")
            ZipOutputStream(missing.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cards.json")); zip.write(archiveJson.encodeToString(ArchiveDocument.serializer(), document).toByteArray()); zip.closeEntry()
            }
            try { ArchiveZip.read(missing) { doc, _ -> doc.summary() }; fail("Accepted missing media") } catch (_: IllegalArgumentException) { }
            document.summary()
        }
        Unit
    }

    @Test fun freshInitializationMatchesDesktop() {
        val now = 1700000000000L
        assertEquals("2023-11-14T22:14:20Z", freshSchedule(now).due)
        assertEquals(2L, freshSchedule(now).remainingSteps)
        assertEquals(1uL, freshSchedule(now).reps)
        assertEquals(true, freshReviews(now).single().automatic)
        assertEquals(0, freshReviews(now).single().log.state)
    }

    @Test fun readsActualDesktopReexportOfNativeAndroidContent() = runBlocking {
        val file = File("src/androidTest/assets/desktop-native-return.zip")
        ArchiveZip.read(file) { document, _ ->
            assertEquals("Native Android", document.folders.single().folder.title)
            assertEquals(3, document.folders.single().cards.single().items.size)
            document.summary()
        }
        Unit
    }

    @Test fun rejectsSymlinksAndManifestChecksumMismatch() = runBlocking {
        val bytes = fixture.readBytes()
        val central = bytes.indices.first { it + 4 <= bytes.size && bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        val link = bytes.copyOf()
        link[central + 5] = 3
        link[central + 40] = 0xff.toByte(); link[central + 41] = 0xa1.toByte()
        val wrongChecksum = bytes.copyOf().also { it[central + 16] = (it[central + 16].toInt() xor 1).toByte() }
        listOf(link, wrongChecksum).forEachIndexed { index, content ->
            val file = File(output, "corrupt-$index.zip").apply { writeBytes(content) }
            try { ArchiveZip.read(file) { doc, _ -> doc.summary() }; fail("Accepted unsafe/corrupt ZIP") } catch (_: IllegalArgumentException) { }
        }
    }
}

private fun ArchiveDocument.summary() = ArchiveResult(folders.size, folders.sumOf { it.cards.size }, ArchiveZip.validate(this).size)
