package com.fishmate.ai.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fishmate.ai.BuildConfig

/**
 * Privacy and About, written as plain prose rather than legalese.
 *
 * These mirror docs/PRIVACY.md. They are in the app because the claims matter
 * to the person using it: where their fishing spots go, and that AI
 * identification can be wrong.
 */

@Composable
fun PrivacyScreen() {
    InfoBody(
        title = "Privacy",
        sections = listOf(
            "Your fishing spots" to
                "Locations are yours. By default FishMate stores an approximate " +
                "position (about a kilometre), which is enough for wind, wave and " +
                "sunrise data but not enough to find your mark. You can choose " +
                "precise, approximate, or no location at all in Settings, and that " +
                "choice is applied before anything leaves the phone.",
            "Nothing is shared" to
                "There is no social feed and no public catch map. Your sessions, " +
                "catches and spots are visible only to your own account.",
            "Voice and camera" to
                "Audio and camera frames are streamed to Google's Gemini Live API " +
                "while Fishing Mode is running, so the assistant can hear and see. " +
                "FishMate does not record audio or store video. Text transcripts of " +
                "the conversation stay on the device unless you save a note.",
            "What the camera sends" to
                "Only low-resolution still frames, and only while you have the camera " +
                "switched on in Fishing Mode. Nothing is captured when Fishing Mode " +
                "is off.",
            "Deleting your data" to
                "Settings → Delete my account and data removes your account and " +
                "everything attached to it, permanently.",
            "AI identification" to
                "Species identification is a suggestion, not a determination. It can " +
                "be wrong, and you can always correct it in the catch log.",
            "Fishing regulations" to
                "FishMate will not state a size limit, closed season or protected " +
                "status it cannot cite. When it cannot verify something, it says so " +
                "and points you at the official authority. Always confirm the rules " +
                "yourself before you keep a fish.",
        ),
    )
}

@Composable
fun AboutScreen() {
    InfoBody(
        title = "About FishMate",
        sections = listOf(
            "What this is" to
                "An AI fishing companion that listens, sees what you show it, " +
                "remembers your previous sessions and gives real-time guidance from " +
                "the shore.",
            "How it works" to
                "Your voice and camera stream directly to Google's Gemini Live API " +
                "over a short-lived credential issued by the FishMate backend. When " +
                "the assistant needs real data — weather, sea state, your tackle, " +
                "your catch history — it calls a tool, which the backend executes " +
                "against live sources and your own records.",
            "Observation, inference, recommendation" to
                "The assistant is built to keep these separate: what it can actually " +
                "see, what it deduces from that, and what it suggests you do. If it " +
                "is unsure, it asks for a better look rather than guessing.",
            "Version" to "FishMate ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
        ),
    )
}

@Composable
private fun InfoBody(title: String, sections: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        sections.forEach { (heading, body) ->
            Text(text = heading, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
