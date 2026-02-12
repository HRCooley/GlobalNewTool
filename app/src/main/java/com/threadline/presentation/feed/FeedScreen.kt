package com.threadline.presentation.feed

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.threadline.domain.model.DiversityLevel
import com.threadline.domain.model.StoryCluster
import java.time.Duration
import java.time.Instant

private val DiverseGreen = Color(0xFF4CAF50)
private val ModerateBlue = Color(0xFF2196F3)
private val ConcentratedYellow = Color(0xFFFFC107)
private val SignalOrange = Color(0xFFFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            state.isLoading && state.clusters.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.clusters.isEmpty() && !state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No stories yet. Pull to refresh.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Scope filter row
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        items(FeedViewModel.SCOPES) { scope ->
                            FilterChip(
                                selected = state.selectedScope == scope,
                                onClick = { viewModel.setScope(scope) },
                                label = { Text(scope, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    // Category filter row
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(FeedViewModel.CATEGORIES) { cat ->
                            FilterChip(
                                selected = state.selectedCategory == cat,
                                onClick = { viewModel.setCategory(cat) },
                                label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    // Cluster list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp
                        )
                    ) {
                        items(state.clusters, key = { it.id }) { cluster ->
                            ClusterCard(cluster = cluster, context = context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterCard(
    cluster: StoryCluster,
    context: android.content.Context
) {
    var expanded by remember { mutableStateOf(false) }
    val diversity = cluster.diversityScore
    val isSignal = diversity?.composite == DiversityLevel.SIGNAL
    val accentColor = when (diversity?.composite) {
        DiversityLevel.DIVERSE -> DiverseGreen
        DiversityLevel.MODERATE -> ModerateBlue
        DiversityLevel.CONCENTRATED -> ConcentratedYellow
        DiversityLevel.SIGNAL -> SignalOrange
        null -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSignal) 4.dp else 2.dp),
        shape = RoundedCornerShape(12.dp),
        border = if (isSignal) BorderStroke(2.dp, SignalOrange) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title
            Text(
                text = cluster.representativeTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) 10 else 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            // Diversity badge + source count + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                val levelIcon = when (diversity?.composite) {
                    DiversityLevel.DIVERSE -> "\uD83D\uDFE2"
                    DiversityLevel.MODERATE -> "\uD83D\uDD35"
                    DiversityLevel.CONCENTRATED -> "\uD83D\uDFE1"
                    DiversityLevel.SIGNAL -> "\uD83D\uDD38"
                    null -> ""
                }
                val levelLabel = diversity?.composite?.name?.lowercase()
                    ?.replaceFirstChar { it.uppercase() } ?: ""

                Text(
                    text = "$levelIcon $levelLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
                Text(
                    text = " \u00B7 ${cluster.sourceCount} source${if (cluster.sourceCount != 1) "s" else ""} \u00B7 ${timeAgo(cluster.mostRecent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Signal badge
            if (isSignal) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "\uD83D\uDD38 Underreported",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SignalOrange
                )
            }

            // Category chips
            val displayCategories = cluster.categories.filter { it != "ALL" }
            if (displayCategories.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    displayCategories.take(3).forEach { cat ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Diversity detail rows
            if (diversity != null && cluster.sourceCount > 1) {
                Spacer(Modifier.height(6.dp))
                DiversityRow("Geographic", diversity.regionCount, accentColor)
                DiversityRow("Political", diversity.leanCount, accentColor)
                DiversityRow("Ownership", diversity.ownerCount, accentColor)
                if (diversity.ownerConcentration != null) {
                    Text(
                        text = diversity.ownerConcentration,
                        style = MaterialTheme.typography.labelSmall,
                        color = ConcentratedYellow
                    )
                }
            }

            // Expanded: show individual sources
            if (expanded && cluster.stories.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sources:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                cluster.stories.forEach { story ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(story.url))
                                context.startActivity(intent)
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = story.sourceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (story.imageUrl != null) {
                            Spacer(Modifier.width(4.dp))
                            AsyncImage(
                                model = story.imageUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            } else if (!expanded && cluster.stories.size > 1) {
                // Tap hint
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap to see all ${cluster.stories.size} sources",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (cluster.stories.size == 1) {
                // Single story — make tappable to open
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cluster.stories[0].sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(cluster.stories[0].url)
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun DiversityRow(label: String, count: Int, color: Color) {
    val indicator = when {
        count >= 4 -> "\uD83D\uDFE2"
        count >= 2 -> "\uD83D\uDFE1"
        else -> "\uD83D\uDD34"
    }
    Row {
        Text(
            text = "$label: $indicator $count",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun timeAgo(instant: Instant): String {
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.toMinutes() < 1 -> "just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m"
        duration.toHours() < 24 -> "${duration.toHours()}h"
        else -> "${duration.toDays()}d"
    }
}
