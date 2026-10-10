package com.fishmate.ai.ui.navigation

/** Every destination in the app, in one place. */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"
    const val PERMISSIONS = "permissions"

    const val HOME = "home"
    const val FISHING = "fishing"
    const val TACKLE = "tackle"
    const val SESSIONS = "sessions"
    const val CATCHES = "catches"
    const val SETTINGS = "settings"

    const val SESSION_DETAIL = "session/{sessionId}"
    const val CATCH_DETAIL = "catch/{catchId}"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val DEMO = "demo"

    fun sessionDetail(id: String) = "session/$id"
    fun catchDetail(id: String) = "catch/$id"

    /** Destinations that show the bottom bar. */
    val TOP_LEVEL = setOf(HOME, TACKLE, SESSIONS, CATCHES, SETTINGS)
}
