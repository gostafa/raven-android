package com.gostafa.raven.features.library

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.database.CardSummary
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.designsystem.RavenTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CardPreviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val fixtures = mutableListOf<File>()
    @After fun cleanup() { fixtures.forEach { it.delete() } }

    private fun part(id: String, type: String = "TEXT", text: String = id, path: String? = null, name: String? = null, order: Int = 0) =
        ContentPartEntity(id, "card", type, text, path, orderIndex = order, fsrsStateJson = "", dueAt = 0, createdAt = 0, updatedAt = 0, originalName = name)
    private fun card(vararg parts: ContentPartEntity) = CardPreview(CardSummary("card", "folder", 0, "Maple Syrup", parts.size, 0, 65), parts.toList())
    private fun wav(): File {
        val samples = 8_000 * 12
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        return File(context.filesDir, "preview-${System.nanoTime()}.wav").apply { writeBytes(buffer.array()); fixtures += this }
    }
    private fun image(): File {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(45, 58, 48))
        paint.color = android.graphics.Color.rgb(193, 181, 146); canvas.drawRect(0f, 250f, 640f, 360f, paint)
        paint.color = android.graphics.Color.rgb(128, 62, 20); canvas.drawRoundRect(230f, 72f, 440f, 390f, 75f, 75f, paint)
        paint.color = android.graphics.Color.rgb(216, 164, 72); canvas.drawRoundRect(299f, 0f, 370f, 105f, 18f, 18f, paint)
        paint.color = android.graphics.Color.rgb(246, 220, 150); paint.textSize = 32f; canvas.drawText("MAPLE", 277f, 215f, paint)
        return File(context.filesDir, "preview-${System.nanoTime()}.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); fixtures += this
        }
    }
    private fun show(value: CardPreview, edit: () -> Unit = {}, practice: () -> Unit = {}, delete: () -> Unit = {}) {
        compose.setContent { RavenTheme { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
            CardRow(value, edit, delete, practice)
        } } }
    }

    @Test fun textOpensPreviewAndMenuKeepsAllActions() {
        var edited = 0; var practiced = 0; var deleted = 0
        show(card(part("Maple Syrup")), { edited++ }, { practiced++ }, { deleted++ })
        compose.onNodeWithText("Maple Syrup").performClick()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed().performClick()
        for (label in listOf("Edit", "Practice", "Delete")) {
            compose.onNodeWithContentDescription("Card actions").performClick()
            compose.onNodeWithText(label, substring = false).performClick()
        }
        compose.runOnIdle { assertEquals(1, edited); assertEquals(1, practiced); assertEquals(1, deleted) }
    }

    @Test fun orderedGridSpansOddFinalPartAndStacksOnNarrowCards() {
        val content = card(part("one", order = 0), part("two", order = 1), part("three", order = 2))
        var narrow by mutableStateOf(false)
        compose.setContent { RavenTheme { Column(Modifier.width(if (narrow) 280.dp else 360.dp).verticalScroll(rememberScrollState())) {
            CardRow(content, {}, {}, {})
        } } }
        val first = compose.onNodeWithText("one").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("two").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("three").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(last.width > first.width * 1.8f)
        compose.runOnIdle { narrow = true }
        val stackedFirst = compose.onNodeWithText("one").fetchSemanticsNode().boundsInRoot
        val stackedSecond = compose.onNodeWithText("two").fetchSemanticsNode().boundsInRoot
        assertTrue(stackedSecond.top > stackedFirst.bottom)
        assertEquals(stackedFirst.width, stackedSecond.width, 1f)
    }

    @Test fun manyPartsRemainReachableAndMediaErrorsCanBeOpenedAndDismissed() {
        show(card(*(0..5).map { part("Part $it", order = it) }.toTypedArray(), part("missing", "IMAGE", path = "missing.png", order = 6)))
        compose.onNodeWithText("Part 5").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Media file is unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Media file is unavailable").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed().performClick()
    }

    @Test fun inlineAudioSupportsSeekingExclusivityAndBackgroundPause() {
        val audio = wav()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val value = card(part("first", "AUDIO", path = audio.name, name = "First.wav"), part("second", "AUDIO", path = audio.name, name = "Second.wav", order = 1))
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { RavenTheme {
            Column(Modifier.fillMaxWidth()) { CardRow(value, {}, {}, {}) }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Play First.wav") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play First.wav").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Pause First.wav").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Seek in First.wav").performSemanticsAction(SemanticsActions.SetProgress) { it(4_000f) }
        compose.onNodeWithContentDescription("Play Second.wav").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Play First.wav").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("Pause Second.wav").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play Second.wav").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play Second.wav").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pause Second.wav").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun imageAndVideoPreviewsOpenAndVideoPausesInlineAudio() {
        val picture = image()
        val audio = wav()
        val video = File(context.filesDir, "preview-${System.nanoTime()}.mp4").apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("preview.mp4").use { input -> outputStream().use { input.copyTo(it) } }
            fixtures += this
        }
        show(card(part("audio", "AUDIO", path = audio.name, name = "First.wav"),
            part("video", "VIDEO", path = video.name, name = "sample.mp4", order = 1),
            part("image", "IMAGE", path = picture.name, name = "Bottle", order = 2)))
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Play First.wav") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play First.wav").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Pause First.wav").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open Video part 2").performClick()
        compose.onNodeWithText("sample.mp4").assertIsDisplayed()
        compose.onNodeWithText("Media file is unavailable").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.onNodeWithContentDescription("Play First.wav").assertExists()
        compose.onNodeWithContentDescription("Bottle").performClick()
        compose.onNodeWithText("Bottle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close preview").performClick()
    }

    @Test fun visualReferenceCard() {
        val args = InstrumentationRegistry.getArguments()
        val locale = args.getString("cardLocale") ?: "en"
        val scale = args.getString("cardFontScale")?.toFloat() ?: 1f
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)); fontScale = scale }
        val localized = context.createConfigurationContext(configuration)
        val audio = wav(); val picture = image()
        val title = if (locale == "ar") "شراب القيقب" else "Maple Syrup"
        val value = card(part("text", text = title), part("audio", "AUDIO", path = audio.name, name = "maple-syrup.wav", order = 1),
            part("image", "IMAGE", path = picture.name, name = "Maple syrup bottle", order = 2))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalResources provides localized.resources, LocalConfiguration provides configuration,
                LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, scale)) {
                RavenTheme { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    CardRow(value, {}, {}, {}, if (locale == "ar") "مفردات" else "Vocabulary")
                } }
            }
        }
        val play = localized.getString(R.string.play_card_audio, "maple-syrup.wav")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(localized.getString(R.string.card_actions)).assertIsDisplayed()
        // Wait for the image to reach the rendered frame, not just for its semantic node to exist.
        compose.onNodeWithContentDescription("Maple syrup bottle").performScrollTo()
        compose.waitUntil(10_000) {
            val bitmap = compose.onNodeWithContentDescription("Maple syrup bottle").captureToImage().asAndroidBitmap()
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != android.graphics.Color.BLACK
        }
        compose.onNodeWithContentDescription(localized.getString(R.string.card_actions)).performScrollTo()
        val profile = args.getString("cardProfile") ?: "phone"
        val output = File(context.getExternalFilesDir(null), "raven-card-$profile.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
