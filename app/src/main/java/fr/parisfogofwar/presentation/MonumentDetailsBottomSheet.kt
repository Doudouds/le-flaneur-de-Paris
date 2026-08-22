package fr.parisfogofwar.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import fr.parisfogofwar.domain.model.Monument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonumentDetailsBottomSheet(
    monument: Monument,
    onDismiss: () -> Unit,
    onLocate: () -> Unit,
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonumentImage(monument)
            Text(
                monument.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { copyText(context, monument.name) },
            )
            val category = monument.category
                ?.takeIf { value -> value.isNotBlank() && value.any(Char::isLetter) }
            val merimeeReference = monument.merimeeReference?.takeIf(String::isNotBlank)
            val historicalDescription = monument.popHistoricalDescription?.takeIf(String::isNotBlank)
            if (category != null || merimeeReference != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    category?.let { value ->
                        Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    }
                    merimeeReference?.let { reference ->
                        FilledTonalButton(
                            onClick = { openPopPage(context, reference) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("+ d'infos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            historicalDescription?.let { description ->
                Text("Description historique", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(description) }
            }
            monument.description
                ?.takeIf(String::isNotBlank)
                ?.let { description ->
                    Text("Description", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(description) }
                }
            if (historicalDescription != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            merimeeReference?.let { reference -> Text("Référence Mérimée : $reference") }
            monument.osmId?.let { Text("OpenStreetMap : $it") }
            Text("${monument.latitude}, ${monument.longitude}")
            monument.imageLicense?.let { license ->
                Text(
                    buildString {
                        append("Photo : ")
                        monument.imageSource?.takeIf(String::isNotBlank)?.let { append(it).append(" — ") }
                        monument.imageAuthor?.takeIf(String::isNotBlank)?.let { append(it).append(" — ") }
                        append(license)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLocate) { Text("Centrer sur le monument") }
                    TextButton(onClick = {
                        val wikipediaUrl = monument.wikipediaUrl?.takeIf(String::isNotBlank)
                            ?: "https://fr.wikipedia.org/w/index.php?search=${Uri.encode(monument.name)}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wikipediaUrl)))
                    }) { Text("Wikipédia") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    monument.imagePageUrl?.let { pageUrl ->
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
                        }) { Text("Source de l’image") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonumentImage(monument: Monument) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
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
            Text("Image indisponible", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Titre du monument", text))
    Toast.makeText(context, "Titre copié", Toast.LENGTH_SHORT).show()
}

private fun openPopPage(context: Context, reference: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pop.culture.gouv.fr/notice/merimee/$reference")))
}
