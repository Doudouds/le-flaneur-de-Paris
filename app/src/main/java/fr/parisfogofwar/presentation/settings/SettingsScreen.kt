package fr.parisfogofwar.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.parisfogofwar.domain.model.ExplorationStatistics
import fr.parisfogofwar.domain.model.GRID_CELL_SIZE_METERS
import fr.parisfogofwar.presentation.AppViewModel
import androidx.compose.material3.LinearProgressIndicator

@Composable
fun SettingsScreen(statistics: ExplorationStatistics, viewModel: AppViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    val offlineMapState by viewModel.offlineMapState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        Text("Taille des cellules : ${GRID_CELL_SIZE_METERS.toInt()} m × ${GRID_CELL_SIZE_METERS.toInt()} m")
        Text("Carte hors connexion", style = MaterialTheme.typography.titleLarge)
        Text("Téléchargez la zone de Paris en Wi-Fi pour utiliser la carte sans connexion. Les monuments et la progression sont déjà stockés localement.")
        when {
            offlineMapState.isAvailable -> {
                Text("Carte de Paris disponible hors connexion.", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = viewModel::deleteOfflineMap) { Text("Supprimer la carte téléchargée") }
            }
            offlineMapState.isDownloading -> {
                Text("Téléchargement en cours…")
                offlineMapState.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            }
            else -> Button(onClick = viewModel::downloadOfflineMap) { Text("Télécharger la carte de Paris") }
        }
        offlineMapState.errorMessage?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { showConfirm = true }) { Text("Supprimer la progression") }
        HorizontalDivider()
        Text("Crédits et licences", style = MaterialTheme.typography.titleLarge)
        Text("© OpenStreetMap contributors — données ODbL")
        Text("Carte : MapLibre Native. Tuiles : fournisseur de style configuré pour le développement.")
        Text("Patrimoine : Ministère de la Culture, export fusionné Mérimée/OpenStreetMap fourni par l’utilisateur.")
        Text("Catalogue local : ${statistics.totalMonuments} monuments dans la bounding box de Paris.", style = MaterialTheme.typography.bodySmall)
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Supprimer la progression ?") },
            text = { Text("Les cellules découvertes et leurs dates seront supprimées. Le catalogue des monuments restera disponible.") },
            confirmButton = { TextButton(onClick = { viewModel.resetProgress(); showConfirm = false }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Annuler") } },
        )
    }
}
