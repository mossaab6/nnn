package com.smartcash.analyzer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartcash.analyzer.AppContainer
import com.smartcash.analyzer.data.settings.ThemeMode
import com.smartcash.analyzer.ui.assistant.AssistantScreen
import com.smartcash.analyzer.ui.home.HomeScreen
import com.smartcash.analyzer.ui.importflow.ImportDialogHost
import com.smartcash.analyzer.ui.ocr.OcrScreen
import com.smartcash.analyzer.ui.onboarding.OnboardingScreen
import com.smartcash.analyzer.ui.people.PeopleScreen
import com.smartcash.analyzer.ui.people.PersonDetailScreen
import com.smartcash.analyzer.ui.settings.NotificationLogScreen
import com.smartcash.analyzer.ui.settings.SettingsScreen
import com.smartcash.analyzer.ui.theme.AppTheme
import com.smartcash.analyzer.ui.transactions.EditTransactionScreen
import com.smartcash.analyzer.ui.transactions.TransactionDetailScreen
import com.smartcash.analyzer.ui.transactions.TransactionsScreen
import kotlinx.coroutines.flow.MutableStateFlow

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** ViewModel created with the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val PEOPLE = "people"
    const val ASSISTANT = "assistant"
    const val SETTINGS = "settings"
    const val OCR = "ocr"
    const val NOTIF_LOG = "notif_log"

    fun tx(id: Long) = "tx/$id"
    fun edit(id: Long = 0, person: String? = null, type: String? = null): String =
        "edit/$id?person=${Uri.encode(person.orEmpty())}&type=${type.orEmpty()}"
    fun person(key: String) = "person/${Uri.encode(key)}"
    fun transactions(filter: String) = "transactions?filter=$filter"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "الرئيسية", Icons.Rounded.Home),
    Tab(Routes.TRANSACTIONS, "المعاملات", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab(Routes.PEOPLE, "الأشخاص", Icons.Rounded.Groups),
    Tab(Routes.ASSISTANT, "المساعد", Icons.Rounded.AutoAwesome),
    Tab(Routes.SETTINGS, "الإعدادات", Icons.Rounded.Settings),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppRoot(container: AppContainer, openTx: MutableStateFlow<Long?>) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    AppTheme(s.accent, s.themeMode, s.pureBlack) {
        val dark = when (s.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                view.context.findActivity()?.window?.let { w ->
                    WindowCompat.getInsetsController(w, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
        }
        CompositionLocalProvider(LocalContainer provides container) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                if (!s.onboarded) {
                    OnboardingScreen()
                } else {
                    MainScaffold(openTx)
                }
                ImportDialogHost()
            }
        }
    }
}

@Composable
private fun MainScaffold(openTx: MutableStateFlow<Long?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val pendingTx by openTx.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTx) {
        pendingTx?.let {
            nav.navigate(Routes.tx(it))
            openTx.value = null
        }
    }
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(
                "${Routes.TRANSACTIONS}?filter={filter}",
                arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "all" }),
            ) { e -> TransactionsScreen(nav, e.arguments?.getString("filter") ?: "all") }
            composable(Routes.PEOPLE) { PeopleScreen(nav) }
            composable(Routes.ASSISTANT) { AssistantScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.OCR) { OcrScreen(nav) }
            composable(Routes.NOTIF_LOG) { NotificationLogScreen(nav) }
            composable("tx/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                TransactionDetailScreen(nav, e.arguments?.getLong("id") ?: 0L)
            }
            composable(
                "edit/{id}?person={person}&type={type}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("person") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                EditTransactionScreen(
                    nav,
                    id = e.arguments?.getLong("id") ?: 0L,
                    presetPerson = e.arguments?.getString("person").orEmpty(),
                    presetType = e.arguments?.getString("type").orEmpty(),
                )
            }
            composable("person/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                PersonDetailScreen(nav, Uri.decode(e.arguments?.getString("key").orEmpty()))
            }
        }
    }
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
