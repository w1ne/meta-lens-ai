package com.metalens.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metalens.app.wakeword.WakeWordPermissions
import com.metalens.app.wakeword.WakeWordService
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metalens.app.R
import com.metalens.app.ui.components.MetaLensTopBar
import com.metalens.app.ui.screens.ConversationScreen
import com.metalens.app.ui.screens.HistoryDetailScreen
import com.metalens.app.ui.screens.HistoryScreen
import com.metalens.app.ui.screens.HomeScreen
import com.metalens.app.ui.screens.SettingsScreen
import com.metalens.app.wearables.WearablesViewModel

sealed class MetaLensRoute(
    val route: String,
    val titleResId: Int,
) {
    data object Home : MetaLensRoute("home", R.string.tab_home)
    data object History : MetaLensRoute("history", R.string.tab_history)
    data object HistoryDetail : MetaLensRoute("history/{conversationId}", R.string.tab_history) {
        fun createRoute(conversationId: String): String = "history/$conversationId"
    }
    data object Settings : MetaLensRoute("settings", R.string.tab_settings)
    data object Stream : MetaLensRoute("stream", R.string.stream_title)
    data object Conversation : MetaLensRoute("conversation", R.string.conversation_title)
    data object PictureAnalysis : MetaLensRoute("picture-analysis", R.string.picture_analysis)
}

private val bottomTabs = listOf(
    MetaLensRoute.Home,
    MetaLensRoute.History,
    MetaLensRoute.Settings,
)

@Composable
fun MetaLensApp() {
    val navController = rememberNavController()
    MetaLensScaffold(navController = navController)
}

@Composable
private fun MetaLensScaffold(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentTab = bottomTabs.firstOrNull { it.route == currentRoute } ?: MetaLensRoute.Home
    val isFullScreenRoute =
        currentRoute == MetaLensRoute.Stream.route ||
            currentRoute == MetaLensRoute.Conversation.route ||
            currentRoute == MetaLensRoute.HistoryDetail.route ||
            currentRoute == MetaLensRoute.PictureAnalysis.route
    val canNavigateBack = navController.previousBackStackEntry != null
    val topBarTitle = stringResource(R.string.home_title)

    Scaffold(
        topBar = {
            if (!isFullScreenRoute) {
                MetaLensTopBar(title = topBarTitle)
            } else {
                MetaLensTopBar(
                    title = topBarTitle,
                    onBack = if (canNavigateBack) ({ navController.popBackStack() }) else null,
                    transparent = true,
                )
            }
        },
        bottomBar = {
            if (!isFullScreenRoute) {
                val navItemColors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    bottomTabs.forEach { tab ->
                        val selected = tab.route == currentRoute
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector =
                                        when (tab) {
                                            MetaLensRoute.Home -> Icons.Filled.Home
                                            MetaLensRoute.History -> Icons.Filled.History
                                            MetaLensRoute.HistoryDetail -> Icons.Filled.History
                                            MetaLensRoute.Settings -> Icons.Filled.Settings
                                            MetaLensRoute.Stream -> Icons.Filled.Home
                                            MetaLensRoute.Conversation -> Icons.Filled.Home
                                            MetaLensRoute.PictureAnalysis -> Icons.Filled.CameraAlt
                                        },
                                    contentDescription = stringResource(tab.titleResId),
                                )
                            },
                            label = {
                                Text(
                                    stringResource(tab.titleResId),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = navItemColors,
                        )
                    }
                }
            }
        },
    ) { padding ->
        MetaLensNavHost(
            navController = navController,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun MetaLensNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = MetaLensRoute.Home.route,
    ) {
        composable(MetaLensRoute.Home.route) {
            val activity = LocalContext.current as ComponentActivity
            val wearablesViewModel: WearablesViewModel = viewModel(activity)
            val wearablesUiState by wearablesViewModel.uiState.collectAsStateWithLifecycle()
            var wakeWordActive by remember { mutableStateOf(false) }

            // Trigger the next missing permission — either a runtime prompt (mic)
            // or a deep-link to Settings (overlay / accessibility).
            var pendingStep by remember { mutableStateOf<WakeWordPermissions.Missing?>(null) }
            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) advanceOrStart(activity, onStart = {
                    WakeWordService.start(activity); wakeWordActive = true
                }, onPending = { pendingStep = it })
            }

            // Re-check permissions on every foreground return (so the user coming
            // back from Settings auto-continues).
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && pendingStep != null) {
                        advanceOrStart(activity, onStart = {
                            WakeWordService.start(activity); wakeWordActive = true; pendingStep = null
                        }, onPending = { pendingStep = it })
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            pendingStep?.let { step ->
                PermissionDialog(
                    step = step,
                    onGrant = {
                        when (step) {
                            WakeWordPermissions.Missing.Mic -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            WakeWordPermissions.Missing.Overlay -> WakeWordPermissions.openOverlaySettings(activity)
                            WakeWordPermissions.Missing.Accessibility -> WakeWordPermissions.openAccessibilitySettings(activity)
                        }
                    },
                    onCancel = { pendingStep = null },
                )
            }

            HomeScreen(
                modifier = modifier,
                isGlassesConnected = wearablesUiState.hasActiveDevice,
                isCapturingPhoto = wearablesUiState.isCapturingPhoto || wearablesUiState.isPreparingPhotoSession,
                isWakeWordActive = wakeWordActive,
                onStartConversation = { navController.navigate(MetaLensRoute.Conversation.route) },
                onStartStreaming = { navController.navigate(MetaLensRoute.Stream.route) },
                onPictureAnalysis = {
                    wearablesViewModel.resetPictureAnalysis()
                    navController.navigate(MetaLensRoute.PictureAnalysis.route)
                },
                onToggleWakeWord = {
                    if (wakeWordActive) {
                        WakeWordService.stop(activity)
                        wakeWordActive = false
                    } else {
                        advanceOrStart(activity, onStart = {
                            WakeWordService.start(activity); wakeWordActive = true
                        }, onPending = { pendingStep = it })
                    }
                },
            )
        }
        composable(MetaLensRoute.Settings.route) {
            SettingsScreen(
                modifier = modifier,
            )
        }
        composable(MetaLensRoute.History.route) {
            HistoryScreen(
                modifier = modifier,
                onOpenConversation = { id ->
                    navController.navigate(MetaLensRoute.HistoryDetail.createRoute(id))
                },
            )
        }
        composable(
            route = MetaLensRoute.HistoryDetail.route,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { entry ->
            val conversationId = entry.arguments?.getString("conversationId").orEmpty()
            HistoryDetailScreen(
                conversationId = conversationId,
                modifier = modifier,
            )
        }
        composable(MetaLensRoute.Stream.route) {
            com.metalens.app.ui.screens.StreamScreen(
                modifier = modifier,
                onStop = { navController.popBackStack() },
            )
        }
        composable(MetaLensRoute.Conversation.route) {
            ConversationScreen(
                modifier = modifier,
                onStop = { navController.popBackStack() },
            )
        }
        composable(MetaLensRoute.PictureAnalysis.route) {
            com.metalens.app.ui.screens.PictureAnalysisScreen(
                modifier = modifier,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MetaLensAppPreview() {
    MetaLensApp()
}

private fun advanceOrStart(
    activity: ComponentActivity,
    onStart: () -> Unit,
    onPending: (WakeWordPermissions.Missing) -> Unit,
) {
    val missing = WakeWordPermissions.missing(activity)
    if (missing.isEmpty()) onStart() else onPending(missing.first())
}

@Composable
private fun PermissionDialog(
    step: WakeWordPermissions.Missing,
    onGrant: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, body, button) = when (step) {
        WakeWordPermissions.Missing.Mic -> Triple(
            "Microphone access",
            "The wake-word detector runs locally on the device's microphone — audio never leaves the phone until you say the trigger word and ChatGPT takes over.",
            "Grant microphone",
        )
        WakeWordPermissions.Missing.Overlay -> Triple(
            "Display over other apps",
            "Android blocks apps from launching ChatGPT from the background unless this permission is on. No overlays are actually drawn — it's only here to allow the launch.",
            "Open Settings",
        )
        WakeWordPermissions.Missing.Accessibility -> Triple(
            "Accessibility: auto-tap voice mode",
            "After ChatGPT opens, this service taps its Voice Mode button for you. It only runs when com.openai.chatgpt is in the foreground and only clicks one button.",
            "Open Accessibility",
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(title) },
        text = { androidx.compose.material3.Text(body) },
        confirmButton = { Button(onClick = onGrant) { androidx.compose.material3.Text(button) } },
        dismissButton = { TextButton(onClick = onCancel) { androidx.compose.material3.Text("Cancel") } },
    )
}

