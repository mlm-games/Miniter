package org.mlm.miniter.ui.components.properties

import org.mlm.miniter.editor.model.RustEqPreset
import org.mlm.miniter.editor.model.RustReverbPreset

val eqPresetOptions: List<Pair<RustEqPreset, String>> = listOf(
    RustEqPreset.Natural to "Natural",
    RustEqPreset.AmRadio to "AM Radio",
    RustEqPreset.BassBooster to "Bass Booster",
    RustEqPreset.BassReducer to "Bass Reducer",
    RustEqPreset.BgmMode to "BGM Mode",
    RustEqPreset.HipHop to "Hip-Hop",
    RustEqPreset.Jazz to "Jazz",
    RustEqPreset.Pop to "Pop",
    RustEqPreset.RnB to "R&B",
    RustEqPreset.Rock to "Rock",
    RustEqPreset.TrebleBooster to "Treble Booster",
    RustEqPreset.TrebleReducer to "Treble Reducer",
    RustEqPreset.Voice to "Voice",
)

val reverbPresetOptions: List<Pair<RustReverbPreset, String>> = listOf(
    RustReverbPreset.Studio to "Studio",
    RustReverbPreset.Bathroom to "Bathroom",
    RustReverbPreset.Cathedral to "Cathedral",
    RustReverbPreset.Cave to "Cave",
    RustReverbPreset.Arena to "Arena",
    RustReverbPreset.Church to "Church",
    RustReverbPreset.ConcertHall to "Concert Hall",
    RustReverbPreset.EchoRoom to "Echo Room",
    RustReverbPreset.HallDelay to "Hall Delay",
    RustReverbPreset.SpringReverb to "Spring Reverb",
)

fun eqPresetLabel(preset: RustEqPreset): String =
    eqPresetOptions.firstOrNull { it.first == preset }?.second ?: preset.name

fun reverbPresetLabel(preset: RustReverbPreset): String =
    reverbPresetOptions.firstOrNull { it.first == preset }?.second ?: preset.name
