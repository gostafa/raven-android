package com.gostafa.raven.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderSoundTest {
    @Test fun preservesExistingCurrentChannelPreferences() = withChannels { context, manager, currentId, legacyId ->
        manager.createNotificationChannel(NotificationChannel(
            legacyId, "Old reminders", NotificationManager.IMPORTANCE_LOW,
        ))
        manager.createNotificationChannel(NotificationChannel(
            currentId, "Review reminders", NotificationManager.IMPORTANCE_HIGH,
        ).apply { setSound(null, notificationAudio()) })

        ensureReminderChannel(context, channelId = currentId, legacyChannelId = legacyId)

        val channel = manager.getNotificationChannel(currentId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
    }

    @Test fun preservesSilentAndBlockedLegacyChannel() = withChannels { context, manager, currentId, legacyId ->
        manager.createNotificationChannel(NotificationChannel(
            legacyId, "Review reminders", NotificationManager.IMPORTANCE_NONE,
        ).apply { setSound(null, notificationAudio()); setShowBadge(false) })

        ensureReminderChannel(context, channelId = currentId, legacyChannelId = legacyId)

        val channel = manager.getNotificationChannel(currentId)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.canShowBadge())
    }

    @Test fun preservesCustomLegacySound() = withChannels { context, manager, currentId, legacyId ->
        manager.createNotificationChannel(NotificationChannel(
            legacyId, "Review reminders", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { setSound(Settings.System.DEFAULT_NOTIFICATION_URI, notificationAudio()); enableVibration(true) })

        repeat(2) { ensureReminderChannel(context, channelId = currentId, legacyChannelId = legacyId) }

        val channel = manager.getNotificationChannel(currentId)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue(channel.shouldVibrate())
    }

    @Test fun repairsUnreadableLegacySound() = withChannels { context, manager, currentId, legacyId ->
        val broken = Uri.parse("android.resource://${context.packageName}/raw/missing_raven")
        manager.createNotificationChannel(NotificationChannel(
            legacyId, "Review reminders", NotificationManager.IMPORTANCE_HIGH,
        ).apply { setSound(broken, notificationAudio()) })

        ensureReminderChannel(context, channelId = currentId, legacyChannelId = legacyId)

        val channel = manager.getNotificationChannel(currentId)
        assertEquals(Uri.parse("android.resource://${context.packageName}/raw/raven"), channel.sound)
        val player = MediaPlayer()
        try {
            val completed = CountDownLatch(1)
            player.setOnCompletionListener { completed.countDown() }
            player.setAudioAttributes(channel.audioAttributes)
            player.setDataSource(context, channel.sound)
            player.prepare()
            assertTrue("The Raven sound must contain playable audio", player.duration > 0)
            player.start()
            assertTrue("The Raven sound must play to completion", completed.await(5, TimeUnit.SECONDS))
        } finally {
            player.release()
        }
    }

    private fun notificationAudio() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private fun withChannels(block: (Context, NotificationManager, String, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefix = "sound-test-${UUID.randomUUID()}/"
        val currentId = prefix + ReminderCoordinator.CHANNEL_ID
        val legacyId = prefix + "review_reminders_v1"
        try {
            block(context, manager, currentId, legacyId)
        } finally {
            manager.deleteNotificationChannel(currentId)
            manager.deleteNotificationChannel(legacyId)
        }
    }
}
