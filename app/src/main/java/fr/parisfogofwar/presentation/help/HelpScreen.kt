package fr.parisfogofwar.presentation.help

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Aide", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Comprendre une fiche monument", style = MaterialTheme.typography.titleLarge)
        Text("Explorez Paris pour dévoiler les secteurs et découvrir les monuments historiques qu’ils contiennent.")
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Tour Eiffel", style = MaterialTheme.typography.headlineSmall)
                Text("Monument historique", style = MaterialTheme.typography.labelLarge)
                Text("Une fiche peut contenir une description, une photo et des liens complémentaires.")
                HorizontalDivider()
                HelpDetail(
                    label = "Référence Mérimée",
                    value = "PA00088801",
                    explanation = "L’identifiant officiel de la fiche du monument dans la base patrimoniale Mérimée.",
                )
                HelpDetail(
                    label = "Coordonnées GPS",
                    value = "48.858261, 2.294504",
                    explanation = "La latitude et la longitude permettent de retrouver précisément le monument sur la carte.",
                )
                HelpDetail(
                    label = "OpenStreetMap",
                    value = "way/5013364",
                    explanation = "La référence de l’objet correspondant dans la base cartographique OpenStreetMap.",
                )
            }
        }

        Text("Notation en étoiles", style = MaterialTheme.typography.titleLarge)
        Text("Les étoiles donnent une indication relative de l’intérêt du monument pour la promenade. Elles combinent notamment la protection patrimoniale, l’ancienneté, la richesse historique, la notoriété et la fréquentation. Une étoile ne signifie pas qu’un monument est sans valeur.")
        RatingLegend(1, "Peu d’intérêt relatif", "Des monuments souvent plus discrets ou moins documentés.", Color(0xFFF7B955))
        RatingLegend(2, "Intérêt notable", "Un monument qui mérite particulièrement le détour.", Color(0xFF64B5F6))
        RatingLegend(3, "Beaucoup d’intérêt", "Un monument particulièrement remarquable, connu ou riche d’histoire.", Color(0xFF8E3A8A))

        Text("Commandes de la carte", style = MaterialTheme.typography.titleLarge)
        HelpIconDetail(
            icon = Icons.Default.LocationOn,
            label = "Suivi GPS en arrière-plan",
            explanation = "Active ou désactive le suivi en arrière-plan. Quand il est actif, l’application continue à vous suivre même si l’écran est verrouillé.",
        )
        HelpIconDetail(
            icon = Icons.Default.Visibility,
            label = "Indices de monuments",
            explanation = "Affiche ou masque les points verts des secteurs encore non découverts qui contiennent des monuments.",
        )
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Carte hors connexion", style = MaterialTheme.typography.titleLarge)
        Text("Téléchargez la zone de Paris en Wi-Fi depuis Réglages pour utiliser la carte sans connexion. Les monuments et la progression sont déjà stockés localement.")
    }
}

@Composable
private fun RatingLegend(stars: Int, label: String, explanation: String, color: Color) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f)),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(starLabel(stars), color = color, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HelpDetail(label: String, value: String, explanation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HelpIconDetail(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    explanation: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun starLabel(stars: Int): String = "★".repeat(stars) + "☆".repeat(3 - stars)
