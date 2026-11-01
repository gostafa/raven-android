package com.gostafa.raven.core.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gostafa.raven.MainActivity
import com.gostafa.raven.R
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.database.ReminderOccurrence
import com.gostafa.raven.core.database.ReminderReceiptEntity
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ReminderInvalidator {
    fun changed()
}

/** One notification per card, including every newly due part of that card. */
data class ReminderDeliveryPlan(
    val batches: Map<String, List<ReminderOccurrence>>,
    val suppressed: List<ReminderOccurrence>,
)

fun planReminderDelivery(occurrences: List<ReminderOccurrence>, activeCard: String?, enabled: Boolean, allowed: Boolean): ReminderDeliveryPlan {
    if (!enabled) return ReminderDeliveryPlan(emptyMap(), emptyList())
    return ReminderDeliveryPlan(
        if (allowed) occurrences.filterNot { it.cardId == activeCard }.groupBy { it.cardId } else emptyMap(),
        occurrences.filter { it.cardId == activeCard },
    )
}

@Singleton
class ActiveReview @Inject constructor() {
    @Volatile var cardId: String? = null
        private set
    fun show(cardId: String?) { this.cardId = cardId }
}

@Singleton
class ReminderCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RavenDatabase,
    private val settings: SettingsRepository,
    private val activeReview: ActiveReview,
) : ReminderInvalidator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    fun start() {
        ensureChannel()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "review-reminder-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build(),
        )
        changed()
    }

    override fun changed() {
        // Persist a recovery request before attempting the fast in-process reconciliation.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "review-reminder-reconcile", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>().build(),
        )
        scope.launch {
            try { reconcile() } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.w("RavenReminders", "Reminder reconciliation will retry", error)
            }
        }
    }

    fun canNotify(): Boolean {
        ensureChannel()
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun canSchedulePrecisely(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun ensureChannel(messages: Context = context) {
        val channel = NotificationChannel(CHANNEL_ID, messages.getString(R.string.review_reminders), NotificationManager.IMPORTANCE_HIGH)
        channel.description = messages.getString(R.string.reminder_explanation)
        channel.setSound(
            Uri.parse("android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(R.raw.raven)}"),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        )
        notifications.createNotificationChannel(channel)
    }

    suspend fun reconcile() = mutex.withLock {
        val enabled = settings.remindersEnabled.first()
        val allowed = canNotify()
        val language = settings.language.first()
        val messages = if (language == "system") context else context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        )
        ensureChannel(messages)
        val now = System.currentTimeMillis()
        database.withTransaction {
            val dao = database.reminderDao()
            val dueCards = if (enabled) dao.dueCardIds(now).toSet() else emptySet()
            notifications.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }.forEach { notice ->
                val cardId = notice.tag.removePrefix(TAG_PREFIX)
                if (cardId !in dueCards || cardId == activeReview.cardId) notifications.cancel(notice.tag, notice.id)
            }
            if (enabled) {
                val pending = dao.pendingDue(now)
                val delivery = planReminderDelivery(pending, activeReview.cardId, enabled, allowed)
                val handled = delivery.suppressed.toMutableList()
                if (allowed) {
                    delivery.batches.forEach { (card, occurrences) ->
                        try {
                            post(card, occurrences, messages)
                            handled.addAll(occurrences)
                        } catch (_: SecurityException) {
                            // Permission may have been revoked between checking and posting.
                        }
                    }
                }
                dao.acknowledge(handled.map { ReminderReceiptEntity(it.partId, it.dueAt) })
            }
            val next = if (enabled && allowed) dao.nextUnhandledDue() else null
            schedule(next)
        }
    }

    private fun post(cardId: String, occurrences: List<ReminderOccurrence>, messages: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_REVIEW_CARD)
            .setData(Uri.parse("raven://review/${Uri.encode(cardId)}"))
            .putExtra(EXTRA_CARD_ID, cardId)
            .putExtra(EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_raven)
            .setContentTitle(messages.getString(R.string.card_ready, occurrences.first().folderName))
            .setContentText(messages.resources.getQuantityString(R.plurals.card_ready_parts, occurrences.size, occurrences.size))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(tap).setAutoCancel(true).build()
        notifications.notify(TAG_PREFIX + cardId, 1, notice)
    }

    private fun schedule(dueAt: Long?) {
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.cancel(pending)
        if (dueAt == null) return
        val at = dueAt.coerceAtLeast(System.currentTimeMillis() + 1_000)
        if (canSchedulePrecisely()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) { /* Access can change while the app runs. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    companion object {
        const val CHANNEL_ID = "review_reminders_v1"
        const val ACTION_REVIEW_CARD = "com.gostafa.raven.REVIEW_DUE_CARD"
        const val EXTRA_CARD_ID = "review_card_id"
        const val EXTRA_REQUEST_ID = "review_request_id"
        const val ACTION_ALARM = "com.gostafa.raven.REVIEW_ALARM"
        const val TAG_PREFIX = "review/"
    }
}
