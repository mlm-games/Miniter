package org.mlm.miniter.settings

import io.github.mlmgames.settings.core.annotations.*
import io.github.mlmgames.settings.core.types.*
import kotlinx.serialization.Serializable
import org.mlm.miniter.editor.model.RustExportFormat

@CategoryDefinition(order = 0, titleKey = MiniterSettingsKeys.CATEGORY_APPEARANCE) object Appearance
@CategoryDefinition(order = 1, titleKey = MiniterSettingsKeys.CATEGORY_EDITOR) object Editor
@CategoryDefinition(order = 2, titleKey = MiniterSettingsKeys.CATEGORY_EXPORT) object Export
@CategoryDefinition(order = 3, titleKey = MiniterSettingsKeys.CATEGORY_PERFORMANCE) object Performance

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
data class AppSettings(
    @Setting(
        title = "Theme",
        titleKey = MiniterSettingsKeys.THEME,
        description = "System / Light / Dark",
        descriptionKey = MiniterSettingsKeys.THEME_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"],
        optionsKey = MiniterSettingsKeys.THEME_OPTIONS
    )
    val themeMode: Int = ThemeMode.Dark.ordinal,

    @Setting(
        title = "Dynamic colors",
        titleKey = MiniterSettingsKeys.DYNAMIC_COLORS,
        description = "Use Material You dynamic colors (Android 12+)",
        descriptionKey = MiniterSettingsKeys.DYNAMIC_COLORS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID]
    )
    val useDynamicColors: Boolean = true,

    @Setting(
        title = "Pure black (OLED)",
        titleKey = MiniterSettingsKeys.OLED_BLACK,
        category = Appearance::class,
        type = Toggle::class,
        key = "oled_black"
    )
    val oledBlack: Boolean = false,

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

//    @Setting(
//        title = "Default export format",
//        description = "Preferred video format for exports",
//        category = Export::class,
//        type = Dropdown::class,
//    )
    val defaultExportFormat: RustExportFormat = RustExportFormat.Av1Mp4,

//    @Setting(
//        title = "Export quality",
//        description = "Default export quality",
//        category = Export::class,
//        type = Slider::class,
//        min = 1f,
//        max = 100f,
//        step = 5f
//    )
    val exportQuality: Float = 80f,

    @Setting(
        title = "Auto-save recovery copy",
        titleKey = MiniterSettingsKeys.AUTO_SAVE_PROJECT,
        description = "Automatically write a recovery copy of project changes",
        descriptionKey = MiniterSettingsKeys.AUTO_SAVE_PROJECT_DESCRIPTION,
        category = Editor::class,
        type = Toggle::class
    )
    val autoSaveProject: Boolean = true,

    @Setting(
        title = "Auto-save interval (seconds)",
        titleKey = MiniterSettingsKeys.AUTO_SAVE_INTERVAL,
        description = "How often to write the recovery copy",
        descriptionKey = MiniterSettingsKeys.AUTO_SAVE_INTERVAL_DESCRIPTION,
        category = Editor::class,
        type = Slider::class,
        min = 10f,
        max = 300f,
        step = 10f,
        dependsOn = "autoSaveProject"
    )
    val autoSaveIntervalSeconds: Float = 60f,

    @Setting(
        title = "Hardware acceleration",
        titleKey = MiniterSettingsKeys.HARDWARE_ACCELERATION,
        description = "Use GPU for video decode/encode (faster, uses more power)",
        descriptionKey = MiniterSettingsKeys.HARDWARE_ACCELERATION_DESCRIPTION,
        category = Performance::class,
        type = Toggle::class
    )
    val hardwareAccelerationEnabled: Boolean = true,

    @Persisted
    val lastProjectPath: String? = null,
)
