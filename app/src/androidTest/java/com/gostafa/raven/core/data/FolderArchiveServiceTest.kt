package com.gostafa.raven.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gostafa.raven.core.archive.*
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.Rating
import com.gostafa.raven.core.reminders.ReminderInvalidator
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FolderArchiveServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: RavenDatabase
    private lateinit var service: FolderArchiveService
    private lateinit var repository: RoomRavenRepository
    private lateinit var temp: File
    private var originalMedia = emptySet<String>()
    private var invalidations = 0
    @Before fun open() {
        temp = File(context.cacheDir, "archive-test-${System.nanoTime()}").apply { mkdirs() }
        originalMedia = File(context.filesDir, "media").list()?.toSet().orEmpty()
        database = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        val reminders = ReminderInvalidator { invalidations++ }
        service = FolderArchiveService(context, database, MediaStorage(context), reminders)
        repository = RoomRavenRepository(database, database.folderDao(), database.cardDao(), database.contentPartDao(), database.mediaDao(), database.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context), reminders)
    }
    @After fun close() {
        database.close()
        temp.deleteRecursively()
        File(context.filesDir, "media").listFiles()?.filterNot { it.name in originalMedia }?.forEach { it.delete() }
    }
    private fun fixture(): Uri {
        val file = File(temp, "desktop.zip")
        InstrumentationRegistry.getInstrumentation().context.assets.open("desktop-folders.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    @Test fun desktopResumeFreshAndRepeatedImportsPreserveCopies() = runBlocking {
        val input = fixture()
        val resumed = service.importFolders(input, ImportMode.RESUME)
        assertEquals(2, resumed.folderCount); assertEquals(1, resumed.cardCount); assertEquals(4, resumed.mediaCount)
        val card = database.cardDao().allInFolder(resumed.folderIds.first()).single()
        val items = database.contentPartDao().forCard(card.id)
        assertEquals(6, items.size)
        assertFalse(items[1].rememberThis)
        assertEquals(1, database.reviewHistoryDao().forPart(items.first().id).size)
        val snapshot = database.archiveStateDao().get(items.first().id)!!
        assertTrue(items.first().dueAt < System.currentTimeMillis())
        val outputDir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path, "archive-compat").apply { mkdirs() }
        val output = File(outputDir, "android-service.zip")
        service.exportFolders(resumed.folderIds, Uri.fromFile(output))
        ArchiveZip.read(output) { document, _ ->
            val item = document.folders.first().cards.single().items.first()
            assertEquals(archiveJson.decodeFromString<PortableSchedule>(snapshot.scheduleJson), item.schedule)
            assertEquals(archiveJson.decodeFromString<List<PortableReview>>(snapshot.reviewsJson), item.reviews)
            ArchiveResult(2, 1, 4)
        }
        val fresh = service.importFolders(input, ImportMode.FRESH)
        val freshCard = database.cardDao().allInFolder(fresh.folderIds.first()).single()
        val freshItem = database.contentPartDao().forCard(freshCard.id).first()
        assertTrue(database.reviewHistoryDao().forPart(freshItem.id).isEmpty())
        assertEquals(1, archiveJson.decodeFromString<List<PortableReview>>(database.archiveStateDao().get(freshItem.id)!!.reviewsJson).size)
        assertTrue(freshItem.dueAt > System.currentTimeMillis())
        service.importFolders(input, ImportMode.RESUME)
        assertEquals(6, repository.observeFolders().first().size)
        assertEquals(snapshot, database.archiveStateDao().get(items.first().id))
        assertEquals(3, invalidations)
    }

    @Test fun contentEditsAndAndroidReviewsRetainPortableHistory() = runBlocking {
        val result = service.importFolders(fixture(), ImportMode.RESUME)
        val card = database.cardDao().allInFolder(result.folderIds.first()).single()
        val items = database.contentPartDao().forCard(card.id)
        val before = database.archiveStateDao().get(items.first().id)!!
        repository.saveCard(card.folderId, card.id, items.map { PartDraft(it.id, com.gostafa.raven.core.model.PartType.valueOf(it.type), it.text, it.mediaPath, it.mimeType, it.rememberThis, it.originalName) })
        assertEquals(before, database.archiveStateDao().get(items.first().id))
        repository.rate(items.first().id, Rating.GOOD)
        val after = database.archiveStateDao().get(items.first().id)!!
        val oldReviews = archiveJson.decodeFromString<List<PortableReview>>(before.reviewsJson)
        val newReviews = archiveJson.decodeFromString<List<PortableReview>>(after.reviewsJson)
        assertEquals(oldReviews, newReviews.dropLast(1))
        assertFalse(newReviews.last().automatic)
        assertEquals(archiveJson.decodeFromString<PortableSchedule>(before.scheduleJson).reps + 1u, archiveJson.decodeFromString<PortableSchedule>(after.scheduleJson).reps)
        repository.deleteFolder(card.folderId)
        assertNull(database.archiveStateDao().get(items.first().id))
    }

    @Test fun failedImportAndFailedDestinationDoNotChangeLibrary() = runBlocking {
        val existingStaging = context.cacheDir.listFiles()!!.map { it.name }.filter { it.startsWith("archive-") }.toSet()
        val bad = File(temp, "bad.zip").apply { writeText("broken") }
        try { service.importFolders(Uri.fromFile(bad), ImportMode.RESUME); fail("Imported broken ZIP") } catch (_: java.util.zip.ZipException) { }
        assertTrue(repository.observeFolders().first().isEmpty())
        val folder = repository.saveFolder(null, "Keep", "", emptyList())
        try { service.exportFolders(listOf(folder), Uri.parse("content://no-such-provider/export")); fail("Accepted unavailable destination") } catch (_: Exception) { }
        assertEquals(listOf(folder), repository.observeFolders().first().map { it.id })
        assertEquals(existingStaging, context.cacheDir.listFiles()!!.map { it.name }.filter { it.startsWith("archive-") }.toSet())
    }

    @Test fun migrationPreservesExistingLibrary() = runBlocking {
        val name = "archive-migration-${System.nanoTime()}.db"
        try {
            Room.databaseBuilder(context, RavenDatabase::class.java, name).build().let { db ->
                db.folderDao().upsert(FolderEntity("keep", "Keep", createdAt = 1, updatedAt = 1))
                db.openHelper.writableDatabase.execSQL("DROP TABLE archive_states")
                db.openHelper.writableDatabase.version = 3
                db.close()
            }
            Room.databaseBuilder(context, RavenDatabase::class.java, name).addMigrations(MIGRATION_3_4).build().let { db ->
                assertEquals("Keep", db.folderDao().get("keep")!!.name)
                assertNull(db.archiveStateDao().get("absent"))
                db.close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun nativeCardsAndSharedMediaExportAsIndependentEntries() = runBlocking {
        val imported = service.importFolders(fixture(), ImportMode.RESUME)
        val original = database.cardDao().allInFolder(imported.folderIds.first()).single()
        val media = database.contentPartDao().forCard(original.id).first { it.mediaPath != null }
        val folder = repository.saveFolder(null, "Native Android", "Portable", listOf("test"))
        repository.saveCard(folder, null, listOf(
            PartDraft(text = "Native text"),
            PartDraft(type = com.gostafa.raven.core.model.PartType.IMAGE, mediaPath = media.mediaPath, mimeType = media.mimeType, originalName = media.originalName),
            PartDraft(type = com.gostafa.raven.core.model.PartType.IMAGE, mediaPath = media.mediaPath, mimeType = media.mimeType, originalName = media.originalName, rememberThis = false),
        ))
        val card = database.cardDao().allInFolder(folder).single()
        repository.rate(database.contentPartDao().forCard(card.id).first().id, Rating.GOOD)
        val outputDir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path, "archive-compat").apply { mkdirs() }
        val output = File(outputDir, "android-native-service.zip")
        val exported = service.exportFolders(listOf(folder, folder), Uri.fromFile(output))
        assertEquals(1, exported.folderCount); assertEquals(2, exported.mediaCount)
        ArchiveZip.read(output) { document, zip ->
            val items = document.folders.single().cards.single().items
            assertNotEquals(items[1].relativePath, items[2].relativePath)
            assertArrayEquals(zip.getInputStream(zip.getEntry(items[1].relativePath)).use { it.readBytes() }, zip.getInputStream(zip.getEntry(items[2].relativePath)).use { it.readBytes() })
            exported
        }
        Unit
    }

    @Test fun databaseFailureRollsBackEveryFolderAndInstalledMedia() = runBlocking {
        val beforeFiles = File(context.filesDir, "media").list()?.toSet().orEmpty()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON content_parts BEGIN SELECT RAISE(ABORT, 'forced import failure'); END")
        try { service.importFolders(fixture(), ImportMode.RESUME); fail("Import should roll back") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(repository.observeFolders().first().isEmpty())
        assertEquals(beforeFiles, File(context.filesDir, "media").list()?.toSet().orEmpty())
        assertEquals(0, invalidations)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun cancellationDuringSourceReadCleansStagingAndLeavesLibraryUntouched() = runBlocking {
        val input = File(requireNotNull(fixture().path)).readBytes()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/zip"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                val pipe = android.os.ParcelFileDescriptor.createPipe()
                kotlin.concurrent.thread(isDaemon = true) {
                    runCatching {
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                            stream.write(input, 0, 16)
                            started.countDown()
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            stream.write(input, 16, input.size - 16)
                        }
                    }
                }
                return pipe[0]
            }
        }
        val resolver = android.content.ContentResolver.wrap(provider)
        val wrapped = object : android.content.ContextWrapper(context) { override fun getContentResolver() = resolver }
        val transfer = FolderArchiveService(wrapped, database, MediaStorage(context), ReminderInvalidator { invalidations++ })
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { transfer.importFolders(Uri.parse("content://archive-test/source"), ImportMode.RESUME) }
        try {
            assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { started.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertTrue(repository.observeFolders().first().isEmpty())
        assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        assertEquals(0, invalidations)
    }

    @Test fun desktopReexportsOfAndroidArchivesImportWithoutLoss() = runBlocking {
        // These are actual pinned-Raven imports/re-exports of native Android and precision archives.
        for (name in listOf("desktop-native-return.zip", "desktop-precision-return.zip")) {
            val input = File(temp, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { source -> input.outputStream().use { source.copyTo(it) } }
            val result = service.importFolders(Uri.fromFile(input), ImportMode.RESUME)
            ArchiveZip.read(input) { document, zip ->
                document.folders.forEachIndexed { folderIndex, expected ->
                    val folder = database.folderDao().get(result.folderIds[folderIndex])!!
                    assertEquals(expected.folder.title, folder.name)
                    val cards = database.cardDao().allInFolder(folder.id)
                    assertEquals(expected.cards.size, cards.size)
                    expected.cards.forEach { sourceCard ->
                        val card = cards.single { it.createdAt == sourceCard.createdAt }
                        val parts = database.contentPartDao().forCard(card.id)
                        assertEquals(sourceCard.items.size, parts.size)
                        sourceCard.items.sortedBy { it.position }.forEachIndexed { index, sourcePart ->
                            val part = parts[index]
                            assertEquals(sourcePart.text, part.text)
                            assertEquals(!sourcePart.hint, part.rememberThis)
                            val portable = database.archiveStateDao().get(part.id)!!
                            assertEquals(sourcePart.schedule, archiveJson.decodeFromString<PortableSchedule>(portable.scheduleJson))
                            assertEquals(sourcePart.reviews, archiveJson.decodeFromString<List<PortableReview>>(portable.reviewsJson))
                            if (sourcePart.relativePath.isNotEmpty()) assertArrayEquals(
                                zip.getInputStream(zip.getEntry(sourcePart.relativePath)).use { it.readBytes() }, MediaStorage(context).file(part.mediaPath!!).readBytes())
                        }
                    }
                }
                result
            }
        }
    }
}
