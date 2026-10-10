package com.fishmate.ai.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fishmate.ai.R
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.ui.theme.GloveFriendlyTouchTarget
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Permission request screen.
 *
 * Each permission is explained in terms of what the angler gets, and only the
 * microphone is required — camera and location are genuinely optional and the
 * app says so rather than nagging. The brief's rule is that permissions are
 * explicit and the UX is honest about what is possible.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val microphone = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    val location = rememberMultiplePermissionsState(
        listOf(
            // Coarse first: ~1 km is enough for weather and sea state.
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.perm_mic_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.perm_mic_title),
            body = stringResource(R.string.perm_mic_body),
            granted = microphone.status.isGranted,
            required = true,
            onGrant = { microphone.launchPermissionRequest() },
        )
        Spacer(Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.CameraAlt,
            title = stringResource(R.string.perm_camera_title),
            body = stringResource(R.string.perm_camera_body),
            granted = camera.status.isGranted,
            required = false,
            onGrant = { camera.launchPermissionRequest() },
        )
        Spacer(Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.LocationOn,
            title = stringResource(R.string.perm_location_title),
            body = stringResource(R.string.perm_location_body),
            granted = location.permissions.any { it.status.isGranted },
            required = false,
            onGrant = { location.launchMultiplePermissionRequest() },
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = microphone.status.isGranted,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GloveFriendlyTouchTarget),
        ) { Text(stringResource(R.string.onboarding_continue)) }

        if (!microphone.status.isGranted) {
            TextButton(
                onClick = onContinue,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.perm_skip)) }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit,
) {
    FishMateCard {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onGrant) {
                        Text(stringResource(R.string.perm_grant))
                    }
                }
            }
        }
    }
}
