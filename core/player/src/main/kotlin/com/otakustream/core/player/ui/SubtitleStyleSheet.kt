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
import com.otakustream.core.player.SubtitleBackground
import com.otakustream.core.player.SubtitleEdgeStyle
import com.otakustream.core.player.SubtitleStyle
import com.otakustream.core.player.SubtitleTextColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleSheet(
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Subtitle style", style = MaterialTheme.typography.titleMedium)

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
            )
        }
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
