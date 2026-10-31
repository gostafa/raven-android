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
import com.gostafa.raven.features.review.ReviewScreen
import java.util.Locale

@Composable
fun RavenRoot(viewModel: LibraryViewModel = hiltViewModel()) {
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
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "library", modifier = Modifier.fillMaxSize()) {
            composable("library") {
                LibraryScreen(
                    viewModel = viewModel,
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
                    )
                }
            }
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onPracticeCard = { navController.navigate("review/card/" + Uri.encode(it)) },
                )
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
                )
            }
        }
    }
}
