package fr.parisfogofwar.presentation.collection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.parisfogofwar.domain.model.Monument

@Composable
fun CollectionScreen(monuments: List<Monument>) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Collection", style = MaterialTheme.typography.headlineMedium)
        Text("Les monuments apparaissent après la découverte de leur secteur.", modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        if (monuments.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aucun monument révélé", style = MaterialTheme.typography.titleMedium)
                    Text("Explorez Paris pour compléter votre collection.")
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(monuments, key = { it.id }) { monument -> MonumentRow(monument) }
            }
        }
    }
}

@Composable
private fun MonumentRow(monument: Monument) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(monument.name, style = MaterialTheme.typography.titleMedium)
                Text(monument.category ?: "Monument historique", style = MaterialTheme.typography.labelMedium)
                Text("Cellule ${monument.cellId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
