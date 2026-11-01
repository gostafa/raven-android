package com.gostafa.raven.core.navigation

import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.gostafa.raven.MainActivity
import com.gostafa.raven.core.reminders.ReminderOffer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gostafa.raven.R
import com.gostafa.raven.features.library.LibraryScreen
import com.gostafa.raven.features.library.LibraryViewModel
import com.gostafa.raven.features.library.FolderScreen
import com.gostafa.raven.features.library.SearchScreen
import com.gostafa.raven.features.library.CardEditorScreen
import com.gostafa.raven.features.library.CardEditorViewModel
import com.gostafa.raven.features.review.ReviewScreen
import java.util.Locale
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.features.library.CardTransferHost

@Composable
fun RavenRoot(
    viewModel: LibraryViewModel = hiltViewModel(),
    reviewRequest: MainActivity.ReviewRequest? = null,
    onReviewRequestConsumed: () -> Unit = {},
) {
    val language by viewModel.settings.language.collectAsState(initial = "system")
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val locale = remember(language, configuration) {
        when (language) {
            "ar" -> Locale.forLanguageTag("ar")
            "en" -> Locale.ENGLISH
            else -> configuration.locales[0] ?: Locale.getDefault()
        }
    }
    val localizedConfiguration = remember(configuration, locale) {
        Configuration(configuration).apply {
            setLocales(LocaleList(locale))
        }
    }
    val localizedContext = remember(context, localizedConfiguration) {
        ContextThemeWrapper(context, R.style.Theme_Raven).apply {
            applyOverrideConfiguration(localizedConfiguration)
        }
    }
    val layoutDirection = if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
        LocalLayoutDirection provides layoutDirection,
    ) {
      RavenTheme {
        val navController = rememberNavController()
        val reviewSelection: (List<String>) -> Unit = { ids ->
            if (ids.isNotEmpty()) {
                navController.navigate("review/selection/_")
                navController.currentBackStackEntry?.savedStateHandle?.set("selected_card_ids", ArrayList(ids.distinct()))
            }
        }
        LaunchedEffect(reviewRequest) {
            reviewRequest?.let {
                navController.navigate(if (it.cardId.isEmpty()) "review/due/_" else "review/due-card/" + Uri.encode(it.cardId)) {
                    popUpTo("library")
                    launchSingleTop = true
                }
                onReviewRequestConsumed()
            }
        }
        NavHost(navController = navController, startDestination = "library", modifier = Modifier.fillMaxSize()) {
            composable("library") {
                LibraryScreen(
                    viewModel = viewModel,
                    reminderOffer = { ReminderOffer() },
                    onReviewDue = { navController.navigate("review/due/_") },
                    onOpenFolder = { navController.navigate("folder/" + Uri.encode(it)) { launchSingleTop = true } },
                    onSearch = { navController.navigate("search") { launchSingleTop = true } },
                )
            }
            composable(
                route = "folder/{folderId}",
                arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
            ) { entry ->
                val folderId = requireNotNull(entry.arguments?.getString("folderId"))
                key(folderId) {
                    FolderScreen(
                        folderId = folderId,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onPracticeFolder = { navController.navigate("review/folder/" + Uri.encode(it)) },
                        onPracticeCard = { navController.navigate("review/card/" + Uri.encode(it)) },
                        onCreateCard = { navController.navigate("card-editor/" + Uri.encode(it)) },
                        onEditCard = { folder, card -> navController.navigate("card-editor/" + Uri.encode(folder) + "?cardId=" + Uri.encode(card)) },
                        onReviewSelection = reviewSelection,
                    )
                }
            }
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onPracticeCard = { navController.navigate("review/card/" + Uri.encode(it)) },
                    onEditCard = { folder, card -> navController.navigate("card-editor/" + Uri.encode(folder) + "?cardId=" + Uri.encode(card)) },
                    onOpenFolder = { navController.navigate("folder/" + Uri.encode(it)) },
                    onReviewSelection = reviewSelection,
                )
            }
            composable(
                route = "card-editor/{folderId}?cardId={cardId}",
                arguments = listOf(
                    navArgument("folderId") { type = NavType.StringType },
                    navArgument("cardId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                CardEditorScreen(hiltViewModel<CardEditorViewModel>(entry), onClosed = { navController.popBackStack() })
            }
            composable(
                route = "review/{mode}/{target}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("target") { type = NavType.StringType },
                ),
            ) { entry ->
                ReviewScreen(
                    mode = entry.arguments?.getString("mode") ?: "due",
                    target = entry.arguments?.getString("target")?.takeUnless { it == "_" },
                    onExit = { navController.popBackStack() },
                    cardIds = entry.savedStateHandle.get<ArrayList<String>>("selected_card_ids").orEmpty(),
                )
            }
        }
        CardTransferHost(viewModel)
      }
    }
}
