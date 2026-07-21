package com.otakustream.core.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otakustream.core.player.EqualizerPreset

private fun EqualizerPreset.label(): String = when (this) {
    EqualizerPreset.FLAT -> "Flat"
    EqualizerPreset.BASS_BOOST -> "Bass boost"
    EqualizerPreset.TREBLE_BOOST -> "Treble boost"
}

// Volume-boost options as LoudnessEnhancer target gains, in millibels (100 mB = 1 dB).
private val VOLUME_BOOST_OPTIONS = listOf(0 to "Off", 600 to "+50%", 1200 to "+100%")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    selectedPreset: EqualizerPreset,
    onSelectPreset: (EqualizerPreset) -> Unit,
    volumeBoostMillibels: Int,
    onSelectVolumeBoost: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Equalizer", style = MaterialTheme.typography.titleMedium)
            EqualizerPreset.entries.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPreset(preset) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = preset == selectedPreset, onClick = { onSelectPreset(preset) })
                    Text(text = preset.label())
                }
            }

            Text(text = "Volume boost", style = MaterialTheme.typography.titleMedium)
            VOLUME_BOOST_OPTIONS.forEach { (millibels, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectVolumeBoost(millibels) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = millibels == volumeBoostMillibels, onClick = { onSelectVolumeBoost(millibels) })
                    Text(text = label)
                }
            }
        }
    }
}
