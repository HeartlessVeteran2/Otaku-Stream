package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MangayomiPreferencesScreen(
    modifier: Modifier = Modifier,
    viewModel: MangayomiPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = uiState.sourceName.ifEmpty { "Extension preferences" },
                style = MaterialTheme.typography.titleMedium,
            )

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (uiState.items.isEmpty()) {
                Text(
                    text = "This extension has no configurable preferences.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                items(uiState.items, key = { it.key }) { item ->
                    when (item) {
                        is PrefItem.ListPref -> ListPrefRow(item, onSelect = { viewModel.onListSelect(item.key, it) })
                        is PrefItem.SwitchPref -> SwitchPrefRow(item, onToggle = { viewModel.onSwitchToggle(item.key, it) })
                        is PrefItem.EditTextPref -> EditTextPrefRow(item, onChange = { viewModel.onEditText(item.key, it) })
                    }
                }
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (uiState.saved) "Saved" else "Save")
            }
        }
    }
}

@Composable
private fun ListPrefRow(item: PrefItem.ListPref, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            Column {
                if (item.summary.isNotEmpty()) Text(item.summary)
                Text(
                    text = item.entries.getOrNull(item.selectedIndex).orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        item.entries.forEachIndexed { index, entry ->
            DropdownMenuItem(
                text = { Text(entry) },
                onClick = {
                    onSelect(index)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun SwitchPrefRow(item: PrefItem.SwitchPref, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = { if (item.summary.isNotEmpty()) Text(item.summary) },
        trailingContent = { Switch(checked = item.checked, onCheckedChange = onToggle) },
    )
}

@Composable
private fun EditTextPrefRow(item: PrefItem.EditTextPref, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge)
        if (item.summary.isNotEmpty()) {
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = item.value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
        )
    }
}
