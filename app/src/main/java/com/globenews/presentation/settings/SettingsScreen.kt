package com.globenews.presentation.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("API Keys", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional: Add API keys for additional news sources.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var newsApiKey by remember { mutableStateOf("") }
            OutlinedTextField(
                value = newsApiKey,
                onValueChange = { newsApiKey = it },
                label = { Text("NewsAPI Key") },
                placeholder = { Text("Enter your newsapi.org key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            var gnewsKey by remember { mutableStateOf("") }
            OutlinedTextField(
                value = gnewsKey,
                onValueChange = { gnewsKey = it },
                label = { Text("GNews API Key") },
                placeholder = { Text("Enter your gnews.io key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Data Sources", style = MaterialTheme.typography.titleMedium)
            Text("GDELT (primary) — no key required", style = MaterialTheme.typography.bodyMedium)
            Text("Google News RSS — no key required", style = MaterialTheme.typography.bodyMedium)
            Text("16 curated international RSS feeds — no key required", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(24.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("GlobeNews v3.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Real-time global news visualization on an interactive map. " +
                    "News sourced from GDELT, Google News, and curated RSS feeds worldwide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
