package com.gostafa.raven.core.companion

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gostafa.raven.MainActivity
import com.gostafa.raven.R
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@AndroidEntryPoint
class CompanionService : Service() {
    @Inject lateinit var repository: RavenRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var visibility: CompanionVisibility
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private var bubble: BubbleView? = null
    private var attached = false
    private var observing = false
    private var stopping = false
    private var permissionListener: android.app.AppOpsManager.OnOpChangedListener? = null
    private var position = CompanionPosition()
    private var availableWidth = 0
    private var availableHeight = 0
    private val bubbleSize get() = (56 * resources.displayMetrics.density).toInt()
    private val params by lazy {
        WindowManager.LayoutParams(bubbleSize, bubbleSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                setTitle("Raven companion")
            }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.companion_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val listener = android.app.AppOpsManager.OnOpChangedListener { operation, packageName ->
            if (operation == android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) scope.launch {
                if (!Settings.canDrawOverlays(this@CompanionService)) stopCompanion(reportFailure = true)
            }
        }
        permissionListener = listener
        getSystemService(android.app.AppOpsManager::class.java).startWatchingMode(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 11, Intent(this, CompanionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_raven)
                .setContentTitle(getString(R.string.companion_running)).setContentText(getString(R.string.companion_notification))
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .addAction(0, getString(R.string.open_raven), open).addAction(0, getString(R.string.stop_companion), stop).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            // Acknowledge every foreground start, including stop/denial requests, before asynchronous cleanup.
            if (stopping || intent?.action == ACTION_STOP) { stopCompanion(); return START_NOT_STICKY }
            if (!Settings.canDrawOverlays(this)) { stopCompanion(reportFailure = true); return START_NOT_STICKY }
            failure.value = false
            running.value = true
            if (!observing) {
                observing = true
                scope.launch {
                    try {
                        combine(repository.observeFolders(), settings.companionPosition, visibility.appVisible, settings.companionEnabled, settings.language) {
                            folders, savedPosition, appVisible, enabled, language ->
                            CompanionState(folders.sumOf { it.dueCount }, savedPosition, appVisible, enabled, language)
                        }.collect { state ->
                            if (!state.enabled || !Settings.canDrawOverlays(this@CompanionService)) { stopCompanion(); return@collect }
                            position = state.position
                            if (state.visible) removeBubble() else showBubble(state.due, state.language)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        stopCompanion(reportFailure = true)
                    }
                }
            }
        } catch (error: RuntimeException) { stopCompanion(reportFailure = true) }
        return START_NOT_STICKY
    }

    private fun showBubble(due: Int, language: String) {
        val locale = when (language) { "ar" -> Locale.forLanguageTag("ar"); "en" -> Locale.ENGLISH; else -> resources.configuration.locales[0] }
        val view = bubble ?: BubbleView(this).also { bubble = it }
        view.due = due
        view.locale = locale
        view.contentDescription = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) }).getString(R.string.companion_due, due)
        placeAtSavedPosition()
        if (!attached) { windows.addView(view, params); attached = true } else windows.updateViewLayout(view, params)
        view.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun updateBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            availableWidth = metrics.bounds.width() - insets.left - insets.right
            availableHeight = metrics.bounds.height() - insets.top - insets.bottom
        } else {
            val display = android.util.DisplayMetrics()
            windows.defaultDisplay.getMetrics(display)
            availableWidth = display.widthPixels
            availableHeight = display.heightPixels - (24 * resources.displayMetrics.density).toInt()
        }
    }

    private fun placeAtSavedPosition() {
        updateBounds()
        val (x, y) = companionCoordinates(availableWidth, availableHeight, bubbleSize, position)
        params.x = x; params.y = y
    }

    private fun removeBubble() {
        if (attached) { runCatching { windows.removeViewImmediate(bubble) }; attached = false }
    }

    private fun stopCompanion(reportFailure: Boolean = false) {
        if (stopping) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        stopping = true
        failure.value = reportFailure
        removeBubble()
        running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch { try { withContext(NonCancellable) { settings.setCompanionEnabled(false) } } finally { stopSelf() } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) { placeAtSavedPosition(); runCatching { windows.updateViewLayout(bubble, params) }.onFailure { stopCompanion(reportFailure = true) } }
    }
    override fun onDestroy() {
        removeBubble(); running.value = false
        permissionListener?.let { getSystemService(android.app.AppOpsManager::class.java).stopWatchingMode(it) }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class BubbleView(context: Context) : View(context) {
        var due = 0
        var locale: Locale = Locale.getDefault()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logo = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        private var downX = 0f; private var downY = 0f
        private var initialX = 0; private var initialY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun onDraw(canvas: Canvas) {
            val size = width.toFloat()
            paint.color = Color.WHITE
            canvas.drawCircle(size / 2, size / 2, size / 2 - 1, paint)
            logo?.setBounds(0, 0, width, height); logo?.draw(canvas)
            if (due > 0) {
                val badge = size * 0.21f
                paint.color = Color.BLACK
                canvas.drawCircle(size - badge, badge, badge, paint)
                paint.color = Color.WHITE; paint.textSize = size * 0.18f; paint.textAlign = Paint.Align.CENTER
                val label = NumberFormat.getIntegerInstance(locale).format(due.coerceAtMost(99)) + if (due > 99) "+" else ""
                canvas.drawText(label, size - badge, badge - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        override fun performClick(): Boolean {
            super.performClick()
            startActivity(Intent(this@CompanionService, MainActivity::class.java).setAction(ACTION_REVIEW_DUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; dragging = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging && attached) {
                        params.x = (initialX + dx.toInt()).coerceIn(0, (availableWidth - width).coerceAtLeast(0))
                        params.y = (initialY + dy.toInt()).coerceIn(0, (availableHeight - height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(this, params) }.onFailure { stopCompanion() }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        position = CompanionPosition(params.x + width / 2 >= availableWidth / 2, params.y.toFloat() / (availableHeight - height).coerceAtLeast(1))
                        placeAtSavedPosition()
                        if (attached) runCatching { windows.updateViewLayout(this, params) }.onFailure { stopCompanion() }
                        scope.launch { settings.setCompanionPosition(position) }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private data class CompanionState(val due: Int, val position: CompanionPosition, val visible: Boolean, val enabled: Boolean, val language: String)
    companion object {
        const val ACTION_REVIEW_DUE = "com.gostafa.raven.REVIEW_DUE"
        private const val ACTION_STOP = "com.gostafa.raven.STOP_COMPANION"
        private const val CHANNEL = "floating_companion"
        private const val NOTIFICATION = 8402
        val running = MutableStateFlow(false)
        val failure = MutableStateFlow(false)
    }
}
