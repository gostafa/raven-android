package com.gostafa.raven

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.gostafa.raven.core.reminders.ReminderCoordinator
import com.gostafa.raven.core.reminders.ActiveReview
import javax.inject.Inject
import com.gostafa.raven.core.data.DueTimeRefresh
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import dagger.hilt.android.AndroidEntryPoint
import com.gostafa.raven.core.navigation.RavenRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var reminders: ReminderCoordinator
    @Inject lateinit var activeReview: ActiveReview
    @Inject lateinit var dueTimeRefresh: DueTimeRefresh
    private val reviewRequest = mutableStateOf<ReviewRequest?>(null)
    private var lastReviewIntent: String? = null

    data class ReviewRequest(val cardId: String, val requestId: Long = System.nanoTime())

    private fun reviewIntentIdentity(intent: Intent?): String? {
        if (intent?.action == com.gostafa.raven.core.companion.CompanionService.ACTION_REVIEW_DUE) return "due"
        if (intent?.action != ReminderCoordinator.ACTION_REVIEW_CARD) return null
        val card = intent.getStringExtra(ReminderCoordinator.EXTRA_CARD_ID) ?: return null
        return card + ":" + intent.getStringExtra(ReminderCoordinator.EXTRA_REQUEST_ID).orEmpty()
    }

    private fun acceptIntent(intent: Intent?) {
        if (intent?.action == com.gostafa.raven.core.companion.CompanionService.ACTION_REVIEW_DUE) {
            lastReviewIntent = "due"
            reviewRequest.value = ReviewRequest("")
        }
        if (intent?.action == ReminderCoordinator.ACTION_REVIEW_CARD) {
            intent.getStringExtra(ReminderCoordinator.EXTRA_CARD_ID)?.let {
                lastReviewIntent = reviewIntentIdentity(intent)
                reviewRequest.value = ReviewRequest(it)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    override fun onResume() { super.onResume(); dueTimeRefresh.refresh(); reminders.changed() }
    override fun onStop() { activeReview.show(null); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_review_card", reviewRequest.value?.cardId)
        outState.putString("last_review_intent", lastReviewIntent)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastReviewIntent = savedInstanceState?.getString("last_review_intent")
        if (savedInstanceState == null || reviewIntentIdentity(intent) != lastReviewIntent) acceptIntent(intent)
        else savedInstanceState.getString("pending_review_card")?.let { reviewRequest.value = ReviewRequest(it) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            RavenRoot(reviewRequest = reviewRequest.value, onReviewRequestConsumed = { reviewRequest.value = null })
        }
    }
}
