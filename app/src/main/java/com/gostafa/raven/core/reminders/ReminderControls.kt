package com.gostafa.raven.core.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.R
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    val settings: SettingsRepository,
    val reminders: ReminderCoordinator,
    database: RavenDatabase,
) : ViewModel() {
    val hasReviewable = database.contentPartDao().observeReviewable().map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun enable(enabled: Boolean) { viewModelScope.launch { settings.setRemindersEnabled(enabled); reminders.changed() } }
    fun dismissOffer() { viewModelScope.launch { settings.markReminderPromptSeen() } }
}

@Composable
fun ReminderOffer(viewModel: ReminderSettingsViewModel = hiltViewModel()) {
    val hasCards by viewModel.hasReviewable.collectAsStateWithLifecycle()
    val seen by viewModel.settings.reminderPromptSeen.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.reminders.changed()
        if (granted && !viewModel.reminders.canSchedulePrecisely()) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
    if (hasCards && !seen) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.review_reminders), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.reminder_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                TextButton(onClick = {
                    viewModel.dismissOffer()
                    viewModel.enable(true)
                    if (Build.VERSION.SDK_INT >= 33 && !viewModel.reminders.canNotify()) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (!viewModel.reminders.canSchedulePrecisely()) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }) { Text(stringResource(R.string.enable_reminders)) }
                TextButton(onClick = viewModel::dismissOffer) { Text(stringResource(R.string.not_now)) }
                }
            }
        }
    }
}

@Composable
fun ReminderControls(viewModel: ReminderSettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.settings.remindersEnabled.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; viewModel.reminders.changed() }
    val notifyAllowed = remember(revision) { viewModel.reminders.canNotify() }
    val preciseAllowed = remember(revision) { viewModel.reminders.canSchedulePrecisely() }
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.review_reminders), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = viewModel::enable)
        }
        Text(stringResource(if (notifyAllowed) R.string.notifications_allowed else R.string.notifications_blocked), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val intent = Intent(if (notifyAllowed) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (notifyAllowed) intent.putExtra(Settings.EXTRA_CHANNEL_ID, ReminderCoordinator.CHANNEL_ID)
            context.startActivity(intent)
        }) { Text(stringResource(R.string.notification_settings)) }
        Text(stringResource(if (preciseAllowed) R.string.precise_allowed else R.string.precise_delayed), style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 31) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.precise_settings)) }
        }
    }
}
