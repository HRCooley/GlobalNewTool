package com.globenews.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.globenews.data.source.local.CustomFeedEntity
import com.globenews.domain.repository.DiagnosticLogEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageFeeds: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val customFeeds by viewModel.customFeeds.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

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
            // Network Diagnostics Section
            Text("Network Diagnostics", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DiagnosticRow("Network Test", diagnostics.networkTest)
                    DiagnosticRow("Pipeline", diagnostics.pipelineSummary)
                    DiagnosticRow("Live Total", diagnostics.liveTotal)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // GDELT detail
                    Text("GDELT", style = MaterialTheme.typography.titleSmall)
                    DiagnosticRow("Status", diagnostics.gdeltStatus)
                    if (diagnostics.gdeltRegionsTotal > 0) {
                        DiagnosticRow(
                            "Regions",
                            "${diagnostics.gdeltRegionsSucceeded} succeeded, " +
                                "${diagnostics.gdeltRegionsFailed} failed of ${diagnostics.gdeltRegionsTotal}"
                        )
                        if (diagnostics.gdeltRegionsRateLimited > 0) {
                            Text(
                                "${diagnostics.gdeltRegionsRateLimited} regions rate-limited (HTTP 429)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // RSS detail
                    Text("RSS Feeds", style = MaterialTheme.typography.titleSmall)
                    DiagnosticRow("Status", diagnostics.rssStatus)
                    if (diagnostics.rssFeedsTotal > 0) {
                        DiagnosticRow(
                            "Feeds",
                            "${diagnostics.rssFeedsSucceeded} succeeded, " +
                                "${diagnostics.rssFeedsFailed} failed, " +
                                "${diagnostics.rssFeedsStillFetching} still fetching"
                        )
                    }
                }
            }

            // Scrollable diagnostics log with copy button
            if (diagnostics.logEntries.isNotEmpty()) {
                val clipboardManager = LocalClipboardManager.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Fetch Log (last ${diagnostics.logEntries.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    OutlinedButton(
                        onClick = {
                            val logText = diagnostics.logEntries.joinToString("\n") { entry ->
                                val tag = if (entry.source == "GDELT") "GDL" else "RSS"
                                "[$tag] ${entry.name}: ${entry.result}"
                            }
                            clipboardManager.setText(AnnotatedString(logText))
                        },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Copy Log", style = MaterialTheme.typography.labelSmall)
                    }
                }
                DiagnosticLogPanel(entries = diagnostics.logEntries)
            }

            HorizontalDivider()

            // Custom RSS Feeds Section
            Text("Custom RSS Feeds", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add your own RSS feed URLs to see local or specialty news on the map.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CustomFeedForm(onAdd = { name, url, lat, lon ->
                viewModel.addFeed(name, url, lat, lon)
            })

            if (customFeeds.isNotEmpty()) {
                Text(
                    "Your Feeds (${customFeeds.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                customFeeds.forEach { feed ->
                    CustomFeedCard(feed = feed, onDelete = { viewModel.deleteFeed(it) })
                }
            }

            HorizontalDivider()

            // Manage Feeds button
            Text("Feed Management", style = MaterialTheme.typography.titleMedium)
            Text(
                "297 bundled RSS feeds with health monitoring. Toggle feeds on/off, add custom feeds, and track broken feeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onManageFeeds,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Feeds")
            }

            HorizontalDivider()

            // API Keys Section
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

            HorizontalDivider()

            Text("Data Sources", style = MaterialTheme.typography.titleMedium)
            Text("GDELT (primary) — no key required", style = MaterialTheme.typography.bodyMedium)
            Text("Google News RSS — no key required", style = MaterialTheme.typography.bodyMedium)
            Text("297 managed RSS feeds — no key required", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(24.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("GlobeNews v3.2", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Real-time global news visualization on an interactive map. " +
                    "News sourced from GDELT, Google News, and curated RSS feeds worldwide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticLogPanel(entries: List<DiagnosticLogEntry>) {
    val listState = rememberLazyListState()
    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(entries) { entry ->
            val tag = if (entry.source == "GDELT") "GDL" else "RSS"
            Text(
                text = "[$tag] ${entry.name}: ${entry.result}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                ),
                color = if (entry.result.startsWith("OK"))
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.error,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CustomFeedForm(onAdd: (String, String, Double, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Feed Name") },
            placeholder = { Text("e.g. My Local Paper") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("RSS Feed URL") },
            placeholder = { Text("https://example.com/feed/rss") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it },
                label = { Text("Latitude") },
                placeholder = { Text("38.03") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = lon,
                onValueChange = { lon = it },
                label = { Text("Longitude") },
                placeholder = { Text("-78.48") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Button(
            onClick = {
                val latitude = lat.toDoubleOrNull() ?: 0.0
                val longitude = lon.toDoubleOrNull() ?: 0.0
                if (name.isNotBlank() && url.isNotBlank()) {
                    onAdd(name.trim(), url.trim(), latitude, longitude)
                    name = ""
                    url = ""
                    lat = ""
                    lon = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add Feed")
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.65f)
        )
    }
}

@Composable
private fun CustomFeedCard(feed: CustomFeedEntity, onDelete: (CustomFeedEntity) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(feed.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    feed.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    "Location: ${feed.latitude}, ${feed.longitude}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onDelete(feed) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete feed",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
