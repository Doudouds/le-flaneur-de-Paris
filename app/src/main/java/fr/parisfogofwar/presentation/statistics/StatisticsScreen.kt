package fr.parisfogofwar.presentation.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.parisfogofwar.domain.model.ExplorationStatistics
import fr.parisfogofwar.domain.model.Monument

@Composable
fun StatisticsScreen(statistics: ExplorationStatistics, revealedMonuments: List<Monument>) {
    val revealedByStars = revealedMonuments.groupingBy { it.stars }.eachCount()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statistiques", style = MaterialTheme.typography.headlineMedium)
        StatCard("Paris découvert", "%.2f %%".format(statistics.percentage))
        StatCard("Secteurs découverts", "${statistics.discoveredCells} / ${statistics.totalCells}")
        Text("Monuments révélés", style = MaterialTheme.typography.titleLarge)
        StatCard("Total", "${statistics.revealedMonuments} / ${statistics.totalMonuments}")
        StatCard("★★★", starProgress(3, revealedByStars, statistics), Color(0xFF8E3A8A))
        StatCard("★★☆", starProgress(2, revealedByStars, statistics), Color(0xFF64B5F6))
        StatCard("★☆☆", starProgress(1, revealedByStars, statistics), Color(0xFFF7B955))
    }
}

private fun starProgress(
    stars: Int,
    revealedByStars: Map<Int?, Int>,
    statistics: ExplorationStatistics,
): String = "${revealedByStars[stars] ?: 0} / ${statistics.totalMonumentsByStars[stars] ?: 0}"

@Composable
private fun StatCard(label: String, value: String, accentColor: Color? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = accentColor ?: LocalContentColor.current)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
