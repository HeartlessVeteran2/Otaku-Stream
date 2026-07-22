package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.otakustream.core.sources.scripting.net.CloudflareSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CloudflareSettingsViewModel @Inject constructor(
    private val settings: CloudflareSettings,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.enabled

    fun setEnabled(value: Boolean) = settings.setEnabled(value)
}

// Settings row for the WebView Cloudflare bypass. Lives in feature:sources because that's where the
// CloudflareSettings dependency is reachable; the app Settings screen renders it.
@Composable
fun CloudflareSettingRow(
    modifier: Modifier = Modifier,
    viewModel: CloudflareSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsState()
    ListItem(
        headlineContent = { Text("Bypass Cloudflare") },
        supportingContent = {
            Text("Solve “Just a moment…” checks in the background so gated sources load")
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
        },
        modifier = modifier.fillMaxWidth(),
    )
}
