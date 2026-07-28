package fr.parisfogofwar.presentation.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.parisfogofwar.domain.model.ExplorationStatistics
import java.text.DateFormat
import java.util.Date

@Composable
fun StatisticsScreen(statistics: ExplorationStatistics) {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statistiques", style = MaterialTheme.typography.headlineMedium)
        StatCard("Paris découvert", "%.2f %%".format(statistics.percentage))
        StatCard("Secteurs découverts", "${statistics.discoveredCells} / ${statistics.totalCells}")
        StatCard("Monuments révélés", "${statistics.revealedMonuments} / ${statistics.totalMonuments}")
        StatCard("Première découverte", statistics.firstDiscoveryMillis?.let { dateFormat.format(Date(it)) } ?: "—")
        StatCard("Dernière découverte", statistics.lastDiscoveryMillis?.let { dateFormat.format(Date(it)) } ?: "—")
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
