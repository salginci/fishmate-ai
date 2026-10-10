package com.fishmate.ai.ui.navigation

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fishmate.ai.R
import com.fishmate.ai.location.LocationPrecision
import com.fishmate.ai.ui.screens.PermissionsScreen
import com.fishmate.ai.ui.screens.auth.AuthScreen
import com.fishmate.ai.ui.screens.catches.CatchDetailScreen
import com.fishmate.ai.ui.screens.catches.CatchLogScreen
import com.fishmate.ai.ui.screens.demo.DemoScreen
import com.fishmate.ai.ui.screens.fishing.FishingModeScreen
import com.fishmate.ai.ui.screens.home.HomeScreen
import com.fishmate.ai.ui.screens.onboarding.OnboardingScreen
import com.fishmate.ai.ui.screens.sessions.SessionDetailScreen
import com.fishmate.ai.ui.screens.sessions.SessionsScreen
import com.fishmate.ai.ui.screens.settings.AboutScreen
import com.fishmate.ai.ui.screens.settings.PrivacyScreen
import com.fishmate.ai.ui.screens.settings.SettingsScreen
import com.fishmate.ai.ui.screens.tackle.TackleScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private data class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val topLevel = listOf(
    TopLevelDestination(Routes.HOME, Icons.Default.Home, R.string.nav_home),
    TopLevelDestination(Routes.TACKLE, Icons.Default.Backpack, R.string.nav_tackle),
    TopLevelDestination(Routes.SESSIONS, Icons.Default.History, R.string.nav_sessions),
    TopLevelDestination(Routes.CATCHES, Icons.Default.Phishing, R.string.nav_catches),
    TopLevelDestination(Routes.SETTINGS, Icons.Default.Settings, R.string.nav_settings),
)

/**
 * Navigation graph.
 *
 * Fishing Mode is deliberately outside the bottom-bar shell: once the angler
 * is in it, there is nothing to navigate to, and chrome would only invite
 * tapping at a screen they cannot look at.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FishMateNavHost(
    /**
     * Required, with no default on purpose. It used to default to
     * [Routes.ONBOARDING], and because the root composable called this with no
     * arguments the app opened on the carousel and then the sign-in screen on
     * every single launch, with a perfectly good 30-day token sitting in
     * DataStore. A default here hides that bug; [RootViewModel] decides.
     */
    startDestination: String,
    navController: NavHostController = rememberNavController(),
    /**
     * Language a newly registered account is created with. English is the
     * product default; the assistant switches to Turkish as soon as the user
     * speaks it, and Settings can change it permanently.
     */
    startLanguage: String = "en",
    /** Records that the carousel has been seen, so a sign-out skips it. */
    onOnboarded: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showShell = currentRoute in Routes.TOP_LEVEL

    val microphone = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    val fineLocation = rememberPermissionState(Manifest.permission.ACCESS_COARSE_LOCATION)

    Scaffold(
        topBar = {
            if (currentRoute != null &&
                currentRoute !in Routes.TOP_LEVEL &&
                currentRoute != Routes.FISHING &&
                currentRoute != Routes.ONBOARDING &&
                currentRoute != Routes.AUTH
            ) {
                TopAppBar(
                    title = { Text(titleFor(currentRoute)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showShell) {
                NavigationBar {
                    topLevel.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Fishing Mode draws edge-to-edge; everything else respects
                // the shell's insets.
                .then(
                    if (currentRoute == Routes.FISHING) Modifier
                    else Modifier.padding(padding),
                ),
        ) {
            NavHost(navController = navController, startDestination = startDestination) {

                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            onOnboarded()
                            navController.navigate(Routes.AUTH)
                        },
                    )
                }

                composable(Routes.AUTH) {
                    AuthScreen(
                        language = startLanguage,
                        onSignedIn = {
                            navController.navigate(Routes.PERMISSIONS) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.PERMISSIONS) {
                    PermissionsScreen(
                        onContinue = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.PERMISSIONS) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        onStartFishing = {
                            if (microphone.status.isGranted) {
                                navController.navigate(Routes.FISHING)
                            } else {
                                // Never drop the user into a mode that cannot
                                // work; explain the requirement instead.
                                navController.navigate(Routes.PERMISSIONS)
                            }
                        },
                        onOpenTackle = { navController.navigate(Routes.TACKLE) },
                        onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                        onOpenCatch = { navController.navigate(Routes.catchDetail(it)) },
                    )
                }

                composable(Routes.FISHING) {
                    FishingModeScreen(
                        onExit = { navController.popBackStack() },
                        cameraPermissionGranted = camera.status.isGranted,
                        locationPrecision = if (fineLocation.status.isGranted) {
                            LocationPrecision.APPROXIMATE
                        } else {
                            LocationPrecision.NONE
                        },
                    )
                }

                composable(Routes.TACKLE) { TackleScreen() }

                composable(Routes.SESSIONS) {
                    SessionsScreen(
                        onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    )
                }

                composable(Routes.CATCHES) {
                    CatchLogScreen(
                        onOpenCatch = { navController.navigate(Routes.catchDetail(it)) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                        onOpenDemo = { navController.navigate(Routes.DEMO) },
                        onSignedOut = {
                            navController.navigate(Routes.AUTH) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.SESSION_DETAIL) { entry ->
                    val id = entry.arguments?.getString("sessionId")
                    if (id != null) SessionDetailScreen(sessionId = id)
                }

                composable(Routes.CATCH_DETAIL) { entry ->
                    val id = entry.arguments?.getString("catchId")
                    if (id != null) {
                        CatchDetailScreen(
                            catchId = id,
                            onDeleted = { navController.popBackStack() },
                        )
                    }
                }

                composable(Routes.PRIVACY) { PrivacyScreen() }
                composable(Routes.ABOUT) { AboutScreen() }
                composable(Routes.DEMO) { DemoScreen() }
            }
        }
    }
}

@Composable
private fun titleFor(route: String): String = when (route) {
    Routes.PRIVACY -> stringResource(R.string.settings_privacy)
    Routes.ABOUT -> stringResource(R.string.settings_about)
    Routes.DEMO -> stringResource(R.string.settings_demo)
    Routes.SESSION_DETAIL -> stringResource(R.string.session_report)
    Routes.CATCH_DETAIL -> stringResource(R.string.nav_catches)
    else -> stringResource(R.string.app_name)
}
