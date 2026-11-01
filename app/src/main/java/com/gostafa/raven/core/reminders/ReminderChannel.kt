package com.gostafa.raven.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import com.gostafa.raven.R

internal fun ensureReminderChannel(
    context: Context,
    messages: Context = context,
    channelId: String = ReminderCoordinator.CHANNEL_ID,
    legacyChannelId: String = "review_reminders_v1",
) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.getNotificationChannel(channelId)?.let { existing ->
        existing.name = messages.getString(R.string.review_reminders)
        existing.description = messages.getString(R.string.reminder_explanation)
        manager.createNotificationChannel(existing)
        return
    }
    // Sound is immutable after registration, so repair old app-resource URIs on a new channel.
    // Keep the old channel: already-visible reminders may still use it.
    val legacy = manager.getNotificationChannel(legacyChannelId)
    val raven = Uri.parse("android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(R.raw.raven)}")
    val channel = NotificationChannel(channelId, messages.getString(R.string.review_reminders),
        legacy?.importance ?: NotificationManager.IMPORTANCE_HIGH)
    channel.description = messages.getString(R.string.reminder_explanation)
    val sound = if (legacy == null ||
        (legacy.sound?.scheme == "android.resource" && legacy.sound?.authority == context.packageName)) {
        raven
    } else {
        legacy.sound
    }
    channel.setSound(
        sound,
        legacy?.audioAttributes ?: AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    )
    legacy?.let {
        channel.enableLights(it.shouldShowLights())
        channel.lightColor = it.lightColor
        channel.vibrationPattern = it.vibrationPattern
        channel.enableVibration(it.shouldVibrate())
        channel.setShowBadge(it.canShowBadge())
        channel.lockscreenVisibility = it.lockscreenVisibility
        channel.setBypassDnd(it.canBypassDnd())
    }
    manager.createNotificationChannel(channel)
}
