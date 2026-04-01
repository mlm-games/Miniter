package org.mlm.miniter.settings

import io.github.mlmgames.settings.core.annotations.*
import io.github.mlmgames.settings.core.types.*
import kotlinx.serialization.Serializable

@CategoryDefinition(order = 0) object Appearance
@CategoryDefinition(order = 1) object Editor
@CategoryDefinition(order = 2) object Export

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
data class AppSettings(
    @Setting(
        title = "Theme",
        description = "System / Light / Dark",
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"]
    )
    val themeMode: Int = ThemeMode.Dark.ordinal,

    // @Setting(
    //     title = "Font size",
    //     description = "UI font size",
    //     category = Appearance::class,
    //     type = Slider::class,
    //     min = 12f,
    //     max = 24f,
    //     step = 1f
    // )
    @Persisted
    val fontSize: Float = 16f,

    @Setting(
        title = "Default export format",
        description = "Preferred video format for exports",
        category = Export::class,
        type = Dropdown::class,
        options = ["MP4", "WebM", "MOV"]
    )
    val defaultExportFormat: Int = 0,

    @Setting(
        title = "Export quality",
        description = "Default export quality",
        category = Export::class,
        type = Slider::class,
        min = 1f,
        max = 100f,
        step = 5f
    )
    val exportQuality: Float = 80f,

    @Setting(
        title = "Auto-save project",
        description = "Automatically save project changes",
        category = Editor::class,
        type = Toggle::class
    )
    val autoSaveProject: Boolean = true,

    @Setting(
        title = "Auto-save interval (seconds)",
        description = "How often to auto-save",
        category = Editor::class,
        type = Slider::class,
        min = 10f,
        max = 300f,
        step = 10f,
        dependsOn = "autoSaveProject"
    )
    val autoSaveIntervalSeconds: Float = 60f,

    @Persisted
    val lastProjectPath: String? = null,
)
