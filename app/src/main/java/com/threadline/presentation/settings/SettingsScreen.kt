package com.threadline.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val diagnostics by viewModel.diagnostics.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Network Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    DiagRow("Network", diagnostics.networkTest)
                    DiagRow("GDELT", diagnostics.gdeltStatus)
                    DiagRow("RSS", diagnostics.rssStatus)
                    if (diagnostics.rssFeedSummary.isNotEmpty()) {
                        DiagRow("Feeds", diagnostics.rssFeedSummary)
                    }
                    DiagRow("Cache", diagnostics.cacheCount)
                    DiagRow("Pipeline", diagnostics.pipelineSummary)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Feed Stats", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val stats by viewModel.feedStats.collectAsState()
                    DiagRow("Enabled feeds", stats.enabledCount.toString())
                    DiagRow("Broken feeds", stats.brokenCount.toString())
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.clearCache() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Cache")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.resetFailures() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Feed Failures")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Threadline v0.1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
