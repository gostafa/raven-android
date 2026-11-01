package com.gostafa.raven.features.review

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.ReviewNavigationEntryPoint
import com.gostafa.raven.MainActivity
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.reminders.ReminderCoordinator
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewNotificationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldAndWarmNotificationRequestsOpenOnlyDuePartsAndSurviveRotation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = EntryPointAccessors.fromApplication(context, ReviewNavigationEntryPoint::class.java)
        val repo = dependencies.repository()
        val oldLanguage = dependencies.settings().language.first()
        dependencies.settings().setLanguage("en")
        val folder = repo.saveFolder(null, "Notification navigation", "", emptyList())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            repo.saveCard(folder, null, listOf(PartDraft(text = "Due notification A"), PartDraft(text = "Future notification A")))
            val a = repo.observeCards(folder).first().single().id
            val future = dependencies.database().contentPartDao().forCard(a)[1]
            dependencies.database().contentPartDao().upsert(future.copy(dueAt = System.currentTimeMillis() + 86_400_000))
            repo.saveCard(folder, null, listOf(PartDraft(text = "Due notification B")))
            val b = repo.observeCards(folder).first().first { it.id != a }.id
            fun request(card: String) = Intent(context, MainActivity::class.java)
                .setAction(ReminderCoordinator.ACTION_REVIEW_CARD)
                .putExtra(ReminderCoordinator.EXTRA_CARD_ID, card)
                .putExtra(ReminderCoordinator.EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario = ActivityScenario.launch(request(a))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Due notification A").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 of 1").assertIsDisplayed()
            compose.onNodeWithText("Future notification A").assertDoesNotExist()
            context.startActivity(request(b))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Due notification B").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Due notification A").assertDoesNotExist()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Due notification B").fetchSemanticsNodes().isNotEmpty() }
            context.startActivity(request("missing"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("All caught up").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            scenario?.close()
            repo.deleteFolder(folder)
            dependencies.settings().setLanguage(oldLanguage)
            dependencies.reminders().changed()
        }
    }
}
