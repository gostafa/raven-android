package com.gostafa.raven.core.companion

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.designsystem.RavenOutlinedButton
import com.gostafa.raven.core.settings.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun CompanionControls(settings: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var failed by remember { mutableStateOf(false) }
    val running by CompanionService.running.collectAsStateWithLifecycle()
    val serviceFailed by CompanionService.failure.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = Settings.canDrawOverlays(context) }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.floating_companion), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.companion_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (allowed) R.string.overlay_allowed else R.string.overlay_blocked), style = MaterialTheme.typography.bodySmall)
        if (!allowed) RavenOutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }) { Text(stringResource(R.string.overlay_settings)) }
        RavenOutlinedButton(onClick = {
            if (running) {
                context.stopService(Intent(context, CompanionService::class.java))
                scope.launch { settings.setCompanionEnabled(false) }
            } else scope.launch {
                try {
                    settings.setCompanionEnabled(true)
                    ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                    failed = false
                } catch (error: RuntimeException) { settings.setCompanionEnabled(false); failed = true }
            }
        }, enabled = allowed || running) { Text(stringResource(if (running) R.string.stop_companion else R.string.start_companion)) }
        if (failed || serviceFailed) Text(stringResource(R.string.companion_failed), color = MaterialTheme.colorScheme.error)
    }
}
