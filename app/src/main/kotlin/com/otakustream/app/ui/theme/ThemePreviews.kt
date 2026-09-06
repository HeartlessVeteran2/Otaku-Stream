package com.otakustream.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// The only way to look at this theme without a device.
//
// Worth having beyond the usual reasons: the bug these previews are meant to catch is a role that
// is never assigned, and an unassigned role doesn't crash or warn — it quietly draws Material's
// baseline purple. Every role gets a labelled swatch below, so a stray purple square is the whole
// diagnosis. ThemeTest asserts the same thing in CI; this is for the half of the problem a test
// can't have an opinion about, which is whether it looks any good.

@Preview(name = "Roles — dark", showBackground = true, heightDp = 1400)
@Composable
private fun ThemeRolesDarkPreview() {
    OtakuStreamTheme(themeMode = ThemeMode.DARK) { RoleSheet() }
}

@Preview(name = "Roles — light", showBackground = true, heightDp = 1400)
@Composable
private fun ThemeRolesLightPreview() {
    OtakuStreamTheme(themeMode = ThemeMode.LIGHT) { RoleSheet() }
}

@Preview(name = "Components — dark", showBackground = true)
@Composable
private fun ComponentsDarkPreview() {
    OtakuStreamTheme(themeMode = ThemeMode.DARK) { ComponentSheet() }
}

@Preview(name = "Components — light", showBackground = true)
@Composable
private fun ComponentsLightPreview() {
    OtakuStreamTheme(themeMode = ThemeMode.LIGHT) { ComponentSheet() }
}

@Composable
private fun RoleSheet() {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            roleTable(scheme).forEach { (name, pair) ->
                Swatch(name = name, fill = pair.first, ink = pair.second)
            }
        }
    }
}

// Every role the schemes assign, paired with the colour meant to be drawn on top of it. Where a
// role has no matching "on" colour of its own (the surface-container family, the outlines) it is
// paired with onSurface, which is what actually sits on it in practice.
private fun roleTable(s: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
    "primary" to (s.primary to s.onPrimary),
    "primaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
    "inversePrimary" to (s.inversePrimary to s.inverseSurface),
    "secondary" to (s.secondary to s.onSecondary),
    "secondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
    "tertiary" to (s.tertiary to s.onTertiary),
    "tertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
    "error" to (s.error to s.onError),
    "errorContainer" to (s.errorContainer to s.onErrorContainer),
    "background" to (s.background to s.onBackground),
    "surface" to (s.surface to s.onSurface),
    "surfaceVariant" to (s.surfaceVariant to s.onSurfaceVariant),
    "surfaceTint" to (s.surfaceTint to s.onSurface),
    "inverseSurface" to (s.inverseSurface to s.inverseOnSurface),
    "surfaceDim" to (s.surfaceDim to s.onSurface),
    "surfaceBright" to (s.surfaceBright to s.onSurface),
    "surfaceContainerLowest" to (s.surfaceContainerLowest to s.onSurface),
    "surfaceContainerLow" to (s.surfaceContainerLow to s.onSurface),
    "surfaceContainer" to (s.surfaceContainer to s.onSurface),
    "surfaceContainerHigh" to (s.surfaceContainerHigh to s.onSurface),
    "surfaceContainerHighest" to (s.surfaceContainerHighest to s.onSurface),
    "outline" to (s.outline to s.surface),
    "outlineVariant" to (s.outlineVariant to s.onSurface),
    "scrim" to (s.scrim to Color.White),
)

@Composable
private fun Swatch(name: String, fill: Color, ink: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(fill, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = name,
            color = ink,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComponentSheet() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cowboy Bebop", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Session 1 — Asteroid Blues",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Play") }
                OutlinedButton(onClick = {}) { Text("Download") }
            }
            // Selected chips are the specific thing to look at here: secondaryContainer was one of
            // the roles that used to fall through to baseline purple, and chips are where that
            // showed most.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Season 1") })
                FilterChip(selected = false, onClick = {}, label = { Text("Season 2") })
                FilterChip(selected = false, onClick = {}, label = { Text("Movies") })
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("2 sources failed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Torrentio — timed out after 15s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // And the nav bar's selected indicator, the other secondaryContainer casualty.
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                    label = { Text("Play") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Explore, contentDescription = null) },
                    label = { Text("Browse") },
                )
            }
        }
    }
}
