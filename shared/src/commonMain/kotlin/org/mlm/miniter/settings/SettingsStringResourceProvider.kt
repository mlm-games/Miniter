package org.mlm.miniter.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import miniter.shared.generated.resources.*
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberSettingsStringResourceProvider(): StringResourceProvider {
    val strings = mapOf(
        MiniterSettingsKeys.CATEGORY_APPEARANCE to stringResource(Res.string.miniter_settings_category_appearance),
        MiniterSettingsKeys.CATEGORY_EDITOR to stringResource(Res.string.miniter_settings_category_editor),
        MiniterSettingsKeys.CATEGORY_EXPORT to stringResource(Res.string.miniter_settings_category_export),
        MiniterSettingsKeys.CATEGORY_PERFORMANCE to stringResource(Res.string.miniter_settings_category_performance),
        MiniterSettingsKeys.THEME to stringResource(Res.string.miniter_settings_theme),
        MiniterSettingsKeys.THEME_DESCRIPTION to stringResource(Res.string.miniter_settings_theme_description),
        MiniterSettingsKeys.DYNAMIC_COLORS to stringResource(Res.string.miniter_settings_dynamic_colors),
        MiniterSettingsKeys.DYNAMIC_COLORS_DESCRIPTION to stringResource(Res.string.miniter_settings_dynamic_colors_description),
        MiniterSettingsKeys.OLED_BLACK to stringResource(Res.string.miniter_settings_oled_black),
        MiniterSettingsKeys.AUTO_SAVE_PROJECT to stringResource(Res.string.miniter_settings_auto_save_project),
        MiniterSettingsKeys.AUTO_SAVE_PROJECT_DESCRIPTION to stringResource(Res.string.miniter_settings_auto_save_project_description),
        MiniterSettingsKeys.AUTO_SAVE_INTERVAL to stringResource(Res.string.miniter_settings_auto_save_interval),
        MiniterSettingsKeys.AUTO_SAVE_INTERVAL_DESCRIPTION to stringResource(Res.string.miniter_settings_auto_save_interval_description),
        MiniterSettingsKeys.HARDWARE_ACCELERATION to stringResource(Res.string.miniter_settings_hardware_acceleration),
        MiniterSettingsKeys.HARDWARE_ACCELERATION_DESCRIPTION to stringResource(Res.string.miniter_settings_hardware_acceleration_description),
    )
    val arrays = mapOf(
        MiniterSettingsKeys.THEME_OPTIONS to stringArrayResource(Res.array.miniter_settings_theme_options),
    )

    return remember(strings, arrays) {
        ComposeResourcesStringResourceProvider(strings, arrays)
    }
}

private class ComposeResourcesStringResourceProvider(
    private val strings: Map<String, String>,
    private val arrays: Map<String, List<String>>,
) : StringResourceProvider {
    override fun getString(resId: Int): String = ""

    override fun getString(resId: Int, vararg formatArgs: Any): String = ""

    override fun getStringArray(resId: Int): List<String> = emptyList()

    override fun getString(key: String): String = strings[key].orEmpty()

    override fun getString(key: String, vararg formatArgs: Any): String {
        val value = getString(key)
        if (formatArgs.isEmpty()) return value
        return FORMAT_REGEX.replace(value) { match ->
            formatArgs.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
        }
    }

    override fun getStringArray(key: String): List<String> = arrays[key].orEmpty()

    private companion object {
        val FORMAT_REGEX = Regex("""%(\d+)\$[ds]""")
    }
}
