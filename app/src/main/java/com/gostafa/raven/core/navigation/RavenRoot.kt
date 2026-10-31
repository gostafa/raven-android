package com.gostafa.raven.core.navigation

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gostafa.raven.features.library.LibraryScreen
import com.gostafa.raven.features.library.LibraryViewModel
import com.gostafa.raven.features.review.ReviewScreen
import java.util.Locale

@Composable
fun RavenRoot(viewModel: LibraryViewModel = hiltViewModel()) {
    val language by viewModel.settings.language.collectAsState(initial = "system")
    val configuration = LocalConfiguration.current
    val locale = remember(language, configuration) {
        when (language) {
            "ar" -> Locale.forLanguageTag("ar")
            "en" -> Locale.ENGLISH
            else -> if (Build.VERSION.SDK_INT >= 24) {
                configuration.locales[0] ?: Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                configuration.locale ?: Locale.getDefault()
            }
        }
    }
    val localizedConfiguration = remember(configuration, locale) {
        Configuration(configuration).apply {
            if (Build.VERSION.SDK_INT >= 24) {
                setLocales(android.os.LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                setLocale(locale)
            }
        }
    }
    val layoutDirection = if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalLayoutDirection provides layoutDirection,
    ) {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "library", modifier = Modifier.fillMaxSize()) {
            composable("library") {
                LibraryScreen(
                    viewModel = viewModel,
                    onReviewDue = { navController.navigate("review/due/_") },
                    onPracticeFolder = { navController.navigate("review/folder/$it") },
                    onPracticeCard = { navController.navigate("review/card/$it") },
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
