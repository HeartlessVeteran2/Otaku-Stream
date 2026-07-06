package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ManageSourcesScreen(
    modifier: Modifier = Modifier,
    viewModel: ManageSourcesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = "Add a source by script URL", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = viewModel::onUrlInputChange,
                    label = { Text("Script URL") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = viewModel::install, enabled = !uiState.isInstalling) {
                    Text("Add")
                }
            }

            if (uiState.isInstalling) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }

            uiState.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            Text(text = "Installed sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

            LazyColumn {
                items(uiState.installed, key = { it.scriptUrl }) { record ->
                    ListItem(
                        headlineContent = { Text(record.name) },
                        supportingContent = { Text(record.scriptUrl) },
                        trailingContent = {
                            TextButton(onClick = { viewModel.remove(record) }) {
                                Text("Remove")
                            }
                        },
                    )
                }
            }
        }
    }
}
