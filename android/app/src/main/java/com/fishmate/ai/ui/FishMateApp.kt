package com.fishmate.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.fishmate.ai.ui.navigation.FishMateNavHost
import com.fishmate.ai.ui.navigation.Routes

@Composable
fun FishMateApp(viewModel: RootViewModel = hiltViewModel()) {
    val startup by viewModel.state.collectAsStateWithLifecycle()

    when (val state = startup) {
        // One frame or two of nothing, which is the point: the graph is built
        // once, from a destination that is already known.
        StartupState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is StartupState.Ready -> {
            val navController = rememberNavController()
            FishMateNavHost(
                startDestination = state.startDestination,
                navController = navController,
                startLanguage = state.language,
                onOnboarded = viewModel::markOnboarded,
            )

            // A token that stops working mid-session has to lead somewhere.
            // popUpTo(0) clears the stack so the back button cannot return to
            // screens that now have nothing to show.
            LaunchedEffect(navController) {
                viewModel.sessionEnded.collect {
                    if (navController.currentDestination?.route != Routes.AUTH) {
                        navController.navigate(Routes.AUTH) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }
    }
}
