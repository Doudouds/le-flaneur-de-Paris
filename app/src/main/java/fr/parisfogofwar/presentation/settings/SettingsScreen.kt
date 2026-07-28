package fr.parisfogofwar.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.parisfogofwar.domain.model.ExplorationStatistics
import fr.parisfogofwar.domain.model.GRID_CELL_SIZE_METERS
import fr.parisfogofwar.presentation.AppViewModel

@Composable
fun SettingsScreen(statistics: ExplorationStatistics, viewModel: AppViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        Text("Taille des cellules : ${GRID_CELL_SIZE_METERS.toInt()} m × ${GRID_CELL_SIZE_METERS.toInt()} m")
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
