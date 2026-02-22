package org.mlm.miniter.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

const val NAV_ANIM_DURATION = 450

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Editor : Route
    @Serializable data object Settings : Route
    @Serializable data object Projects : Route
    @Serializable data class Project(val path: String, val name: String) : Route
}

fun loginEntryFadeMetadata(): Map<String, Any> {
    val fade: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        fadeIn(tween(NAV_ANIM_DURATION)) togetherWith fadeOut(tween(NAV_ANIM_DURATION))
    }
    return mapOf(
        "transitionSpec" to fade,
        "popTransitionSpec" to fade
    )
}

val routeSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Route.Editor::class, Route.Editor.serializer())
        subclass(Route.Settings::class, Route.Settings.serializer())
        subclass(Route.Projects::class, Route.Projects.serializer())
        subclass(Route.Project::class, Route.Project.serializer())
    }
}

val navSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = routeSerializersModule
}
