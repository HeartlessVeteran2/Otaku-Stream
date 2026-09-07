package com.otakustream.core.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.otakustream.core.player.SubtitleBackground
import com.otakustream.core.player.SubtitleEdgeStyle
import com.otakustream.core.player.SubtitleStyle
import com.otakustream.core.player.SubtitleTextColor
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleSheet(
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Subtitle style", style = MaterialTheme.typography.titleMedium)
            SubtitleStyleControls(style = style, onStyleChange = onStyleChange)
        }
    }
}

// The controls themselves, without the sheet around them.
//
// Split out so Settings can host the same thing. Subtitle appearance used to be reachable only from
// the track menu of a video that was already playing, which meant the only way to set up subtitles
// was to start something, fiddle mid-episode, and hope it looked right — and someone who wanted to
// check the app's settings before watching anything could not find them at all. Two copies of these
// controls would have drifted; one composable in two hosts cannot.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleControls(
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {

            // Live preview over a dark strip so colors and outlines read the same as on video.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .background(Color(0xFF202020)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .background(Color(style.background.argb)),
                ) {
                    Text(
                        text = "The quick brown fox",
                        color = Color(style.textColor.argb),
                        fontSize = (18 * style.textScale).sp,
                        style = TextStyle(shadow = style.edgeStyle.previewShadow()),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(text = "Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = style.textScale,
                onValueChange = { onStyleChange(style.copy(textScale = it)) },
                valueRange = SubtitleStyle.MIN_TEXT_SCALE..SubtitleStyle.MAX_TEXT_SCALE,
                // Without this a screen reader announces a bare percentage of the range — "62
                // percent" — which says nothing about text size. PlayerControlsOverlay already
                // does this for the scrubber; these two sliders were the ones that missed it.
                modifier = Modifier.semantics {
                    stateDescription = "${(style.textScale * 100).roundToInt()}% of normal size"
                },
            )

            Text(text = "Outline", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = SubtitleEdgeStyle.entries,
                selected = style.edgeStyle,
                label = { it.label },
                onSelect = { onStyleChange(style.copy(edgeStyle = it)) },
            )

            Text(text = "Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            ChipRow(
                options = SubtitleTextColor.entries,
                selected = style.textColor,
                label = { it.label },
                onSelect = { onStyleChange(style.copy(textColor = it)) },
            )

            Text(text = "Background", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            ChipRow(
                options = SubtitleBackground.entries,
                selected = style.background,
                label = { it.label },
                onSelect = { onStyleChange(style.copy(background = it)) },
            )

            Text(text = "Bottom margin", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = style.bottomMarginFraction,
                onValueChange = { onStyleChange(style.copy(bottomMarginFraction = it)) },
                valueRange = 0f..SubtitleStyle.MAX_BOTTOM_MARGIN,
                modifier = Modifier.semantics {
                    stateDescription = "${(style.bottomMarginFraction * 100).roundToInt()}% up from the bottom"
                },
            )
    }
}

// Approximate each Media3 edge type in the preview so the choice is legible before playback:
// Compose text can't stroke an outline, but a tight vs. offset shadow reads the difference.
private fun SubtitleEdgeStyle.previewShadow(): Shadow? = when (this) {
    SubtitleEdgeStyle.NONE -> null
    SubtitleEdgeStyle.OUTLINE -> Shadow(color = Color.Black, blurRadius = 3f)
    SubtitleEdgeStyle.DROP_SHADOW -> Shadow(color = Color.Black, offset = Offset(4f, 4f), blurRadius = 4f)
    SubtitleEdgeStyle.RAISED -> Shadow(color = Color.Black, offset = Offset(-2f, -2f), blurRadius = 1f)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
