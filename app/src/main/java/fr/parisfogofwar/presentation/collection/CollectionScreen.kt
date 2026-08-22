package fr.parisfogofwar.presentation.collection

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import fr.parisfogofwar.domain.model.Monument
import fr.parisfogofwar.presentation.MonumentDetailsBottomSheet

@Composable
fun CollectionScreen(
    monuments: List<Monument>,
    arrondissementTotals: Map<Int?, Int>,
    onImageNeeded: (Monument) -> Unit,
    onLocate: (Monument) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(COLLECTION_PREFERENCES, 0) }
    var sortMode by rememberSaveable { mutableIntStateOf(preferences.getInt(SORT_MODE_KEY, SORT_BY_DISCOVERY)) }
    val sortedMonuments = when (sortMode) {
        SORT_BY_ARRONDISSEMENT -> monuments.sortedWith(
            compareBy<Monument> { it.arrondissement ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        else -> monuments.sortedWith(
            compareBy<Monument> { it.discoveredAtEpochMillis ?: Long.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
    val discoveredByArrondissement = monuments.groupingBy { it.arrondissement }.eachCount()
    var selectedMonumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedMonument = selectedMonumentId?.let { id -> monuments.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Collection", style = MaterialTheme.typography.headlineMedium)
        Text("Les monuments apparaissent après la découverte de leur secteur.", modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Text("Trier par", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(
                selected = sortMode == SORT_BY_DISCOVERY,
                onClick = {
                    sortMode = SORT_BY_DISCOVERY
                    preferences.edit().putInt(SORT_MODE_KEY, SORT_BY_DISCOVERY).apply()
                },
                label = { Text("Découverte") },
            )
            FilterChip(
                selected = sortMode == SORT_BY_ARRONDISSEMENT,
                onClick = {
                    sortMode = SORT_BY_ARRONDISSEMENT
                    preferences.edit().putInt(SORT_MODE_KEY, SORT_BY_ARRONDISSEMENT).apply()
                },
                label = { Text("Arrondissement") },
            )
        }
        if (monuments.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aucun monument révélé", style = MaterialTheme.typography.titleMedium)
                    Text("Explorez Paris pour compléter votre collection.")
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(sortedMonuments, key = { _, monument -> monument.id }) { index, monument ->
                    if (sortMode == SORT_BY_ARRONDISSEMENT) {
                        val previousArrondissement = sortedMonuments.getOrNull(index - 1)?.arrondissement
                        if (index == 0 || monument.arrondissement != previousArrondissement) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    arrondissementTitle(
                                        monument.arrondissement,
                                        discoveredByArrondissement[monument.arrondissement] ?: 0,
                                        arrondissementTotals[monument.arrondissement]
                                            ?: discoveredByArrondissement[monument.arrondissement]
                                            ?: 0,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                HorizontalDivider(color = Color.Black, thickness = 1.dp)
                            }
                        }
                    }
                    MonumentRow(monument, onImageNeeded, onLocate) { selectedMonumentId = monument.id }
                }
            }
        }
    }

    selectedMonument?.let { monument ->
        MonumentDetailsBottomSheet(
            monument = monument,
            onDismiss = { selectedMonumentId = null },
            onLocate = {
                selectedMonumentId = null
                onLocate(monument)
            },
        )
    }
}

@Composable
private fun MonumentRow(
    monument: Monument,
    onImageNeeded: (Monument) -> Unit,
    onLocate: (Monument) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(monument.id, monument.imageUrl) { onImageNeeded(monument) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MonumentThumbnail(monument)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(monument.name, style = MaterialTheme.typography.titleMedium)
                monument.category
                    ?.takeIf { category -> category.isNotBlank() && category.any(Char::isLetter) }
                    ?.let { category ->
                        Text(category, style = MaterialTheme.typography.labelMedium)
                    }
                monument.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onLocate(monument) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Localiser")
                    }
                    TextButton(
                        onClick = {
                            val wikipediaUrl = monument.wikipediaUrl?.takeIf(String::isNotBlank)
                                ?: "https://fr.wikipedia.org/w/index.php?search=${Uri.encode(monument.name)}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wikipediaUrl)))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Wikipédia")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonumentThumbnail(monument: Monument) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 76.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (monument.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(monument.imageUrl)
                    .diskCacheKey("monument-${monument.id}")
                    .build(),
                contentDescription = "Photo de ${monument.name}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(Icons.Default.BrokenImage, contentDescription = "Image indisponible", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private const val SORT_BY_DISCOVERY = 0
private const val SORT_BY_ARRONDISSEMENT = 1
private const val COLLECTION_PREFERENCES = "collection_preferences"
private const val SORT_MODE_KEY = "sort_mode"

private fun arrondissementLabel(number: Int?): String = when (number) {
    1 -> "1er arrondissement"
    null -> "Hors arrondissement"
    else -> "${number}e arrondissement"
}

private fun arrondissementTitle(number: Int?, discovered: Int, total: Int): String {
    val remaining = (total - discovered).coerceAtLeast(0)
    return "${arrondissementLabel(number)} — $discovered/$remaining"
}
