package com.globenews.presentation.feeds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.globenews.data.source.local.ManagedFeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedManagementScreen(
    onBack: () -> Unit,
    viewModel: FeedManagementViewModel = hiltViewModel()
) {
    val allFeeds by viewModel.allFeeds.collectAsState()
    val brokenCount by viewModel.brokenCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showBrokenOnly by viewModel.showBrokenOnly.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val enabledCount by remember(allFeeds) {
        derivedStateOf { allFeeds.count { it.enabled } }
    }

    val filteredFeeds by remember(allFeeds, searchQuery, showBrokenOnly) {
        derivedStateOf {
            var feeds = allFeeds
            if (showBrokenOnly) {
                feeds = feeds.filter { it.consecutiveFailures >= 3 }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                feeds = feeds.filter {
                    it.name.lowercase().contains(q) ||
                        it.category.lowercase().contains(q) ||
                        it.country.lowercase().contains(q)
                }
            }
            feeds
        }
    }

    val groupedFeeds by remember(filteredFeeds) {
        derivedStateOf {
            filteredFeeds.groupBy { it.category }
                .toSortedMap()
                .map { (category, feeds) -> category to feeds }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Manage Feeds", maxLines = 1)
                        Text(
                            "$enabledCount enabled, $brokenCount broken",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (showBrokenOnly) "Show all feeds" else "Show broken only")
                                },
                                onClick = {
                                    viewModel.toggleBrokenOnly()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Re-enable all broken") },
                                onClick = {
                                    viewModel.resetBroken()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import bundled feeds") },
                                onClick = {
                                    viewModel.reimportBundled()
                                    showMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add Custom Feed")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search feeds...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true
            )

            // Feed list grouped by category
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                groupedFeeds.forEach { (category, feeds) ->
                    item(key = "header_$category") {
                        Text(
                            text = formatCategoryName(category),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(feeds, key = { it.id }) { feed ->
                        FeedRow(
                            feed = feed,
                            onToggle = { viewModel.toggleFeed(feed.id, it) },
                            onDelete = if (!feed.isBundled) {
                                { viewModel.deleteFeed(feed) }
                            } else null
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFeedDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, lat, lon, category ->
                viewModel.addCustomFeed(name, url, lat, lon, category)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun FeedRow(
    feed: ManagedFeed,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Health indicator
            val healthColor = when {
                feed.consecutiveFailures >= 3 -> Color.Red
                feed.consecutiveFailures >= 1 -> Color(0xFFFF9800)
                feed.totalSuccesses > 0 -> Color(0xFF4CAF50)
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(healthColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Feed info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = countryFlag(feed.country),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = feed.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = feed.scope,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (feed.consecutiveFailures >= 3 && !feed.lastError.isNullOrBlank()) {
                    Text(
                        text = feed.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Delete button for user-added feeds
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Enable/disable toggle
            Switch(
                checked = feed.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun AddFeedDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Double, Double, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("RSS URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Lat") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Lon") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onAdd(
                            name.trim(),
                            url.trim(),
                            lat.toDoubleOrNull() ?: 0.0,
                            lon.toDoubleOrNull() ?: 0.0,
                            category.trim()
                        )
                    }
                }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatCategoryName(category: String): String {
    return category.replace("_", " ")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val code = countryCode.uppercase()
    return try {
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        String(first) + String(second)
    } catch (e: Exception) {
        ""
    }
}
