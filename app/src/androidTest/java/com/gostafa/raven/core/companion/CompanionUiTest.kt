package com.gostafa.raven.core.companion

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import com.gostafa.raven.MainActivity
import com.gostafa.raven.ReviewNavigationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CompanionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val settings get() = EntryPointAccessors.fromApplication(context, ReviewNavigationEntryPoint::class.java).settings()
    private var activity: MainActivity? = null
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun overlayVisible() = shell("dumpsys window windows").contains("Raven companion")

    @Before fun prepare() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            // Overlay navigation updates MainActivity's Intent; ActivityScenario cannot track that intent change.
            Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            activity?.finish()
        }
        context.stopService(Intent(context, CompanionService::class.java))
        runBlocking { settings.setCompanionEnabled(false) }
        shell("settings put system user_rotation 0")
        shell("settings put system accelerometer_rotation 1")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }
    private fun start() {
        runBlocking { settings.setCompanionEnabled(true) }
        instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java)) }
        compose.waitUntil(10_000) { CompanionService.running.value }
    }

    @Test fun bubbleHidesInRavenShowsOverHomeAndOpensReview() {
        runBlocking { settings.setCompanionPosition(CompanionPosition(true, 0.5f)) }
        start()
        compose.waitUntil(5_000) { !overlayVisible() }
        shell("input keyevent KEYCODE_HOME")
        compose.waitUntil(10_000) { overlayVisible() }
        // A WindowManager entry precedes its first rendered frame and input readiness.
        val density = context.resources.displayMetrics.density
        compose.waitUntil(10_000) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            val sampleX = (frame.width - 48 * density).toInt().coerceIn(0, frame.width - 1)
            val visible = frame.getPixel(sampleX, frame.height / 2) == android.graphics.Color.WHITE
            frame.recycle()
            visible
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = java.io.File(context.getExternalFilesDir(null), "raven-phone-companion.png")
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val position = runBlocking { settings.companionPosition.first() }
        val x = if (position.rightEdge) screenshot.width - (28 * density).toInt() else (28 * density).toInt()
        val y = (screenshot.height * position.verticalFraction).toInt()
        shell("input tap $x $y")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close review").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.waitUntil(5_000) { !overlayVisible() }
    }

    @Test fun permissionRevocationStopsServiceAndDeniedStartDoesNotLeaveWindow() {
        start()
        shell("input keyevent KEYCODE_HOME")
        compose.waitUntil(10_000) { overlayVisible() }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        compose.waitUntil(10_000) { !CompanionService.running.value && !overlayVisible() && !runBlocking { settings.companionEnabled.first() } }
        assertFalse(runBlocking { settings.companionEnabled.first() })
        instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java)) }
        compose.waitUntil(5_000) { !CompanionService.running.value && !overlayVisible() }
    }

    @Test fun dragPersistsEdgeRotationClampsAndNotificationStopRemovesBubble() {
        runBlocking { settings.setCompanionPosition(CompanionPosition(true, 0.5f)) }
        start()
        shell("input keyevent KEYCODE_HOME")
        compose.waitUntil(10_000) { overlayVisible() }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val density = context.resources.displayMetrics.density
        val startX = screenshot.width - (28 * density).toInt()
        val startY = screenshot.height / 2
        shell("input swipe $startX $startY 20 300 700")
        compose.waitUntil(5_000) { !runBlocking { settings.companionPosition.first() }.rightEdge }
        val saved = runBlocking { settings.companionPosition.first() }
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 1")
        compose.waitUntil(5_000) { overlayVisible() }
        assertEquals(saved, runBlocking { settings.companionPosition.first() })
        val notifications = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        val stop = notifications.first { it.notification.channelId == "floating_companion" }.notification.actions.last().actionIntent
        stop.send()
        compose.waitUntil(10_000) { !CompanionService.running.value && !overlayVisible() && !runBlocking { settings.companionEnabled.first() } }
        assertFalse(runBlocking { settings.companionEnabled.first() })
    }
}
