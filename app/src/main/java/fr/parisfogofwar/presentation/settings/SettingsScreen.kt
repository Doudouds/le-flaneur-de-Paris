package fr.parisfogofwar.presentation.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.ExplorationStatistics
import fr.parisfogofwar.presentation.AppViewModel
import androidx.compose.material3.LinearProgressIndicator

@Composable
fun SettingsScreen(statistics: ExplorationStatistics, viewModel: AppViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    val appState by viewModel.uiState.collectAsStateWithLifecycle()
    val offlineMapState by viewModel.offlineMapState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    var backgroundLocationAllowed by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    var enableBackgroundAfterNotificationPermission by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundLocationAllowed = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        if (granted && enableBackgroundAfterNotificationPermission) viewModel.setBackgroundTrackingEnabled(true)
        enableBackgroundAfterNotificationPermission = false
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
                enableBackgroundAfterNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setBackgroundTrackingEnabled(true)
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Notifications", style = MaterialTheme.typography.titleLarge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notificationsAllowed) {
                Text("Les notifications de nouveaux monuments sont activées.", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Autorisez les notifications pour être prévenu lorsqu'un monument est découvert.")
                Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Activer les notifications")
                }
            }
        } else {
            Text("Les notifications de nouveaux monuments sont disponibles sur cet appareil.")
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Suivi en arrière-plan", style = MaterialTheme.typography.titleLarge)
        Text("L'application peut continuer à dévoiler la carte lorsque l'écran est verrouillé. Android affichera une notification permanente et la batterie sera davantage sollicitée.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationAllowed) {
            Text("Pour autoriser la localisation en permanence, ouvrez les réglages Android de l'application puis choisissez « Autoriser tout le temps » dans les permissions de localisation.")
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                },
            ) { Text("Ouvrir les réglages Android") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (appState.backgroundTrackingEnabled) "Suivi actif" else "Suivi désactivé")
            Switch(
                checked = appState.backgroundTrackingEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        viewModel.setBackgroundTrackingEnabled(false)
                    } else {
                        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasLocationPermission) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
                                enableBackgroundAfterNotificationPermission = true
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setBackgroundTrackingEnabled(true)
                            }
                        }
                        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
            )
        }
        if (appState.backgroundTrackingEnabled) {
            Text("Pour arrêter le suivi, désactivez ce bouton.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Version", style = MaterialTheme.typography.titleLarge)
        Text("Version de l’application : ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text("Aide", style = MaterialTheme.typography.titleLarge)
        Text("Voici comment lire les informations d’un monument dans l’application.")
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Tour Eiffel", style = MaterialTheme.typography.headlineSmall)
                Text("Monument historique", style = MaterialTheme.typography.labelLarge)
                Text("Un monument découvert s’affiche ainsi dans sa fiche :")
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
                Text("Selon les monuments, une description, une photo ou des liens complémentaires peuvent aussi apparaître.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Commandes de la carte", style = MaterialTheme.typography.titleMedium)
        HelpIconDetail(
            icon = Icons.Default.LocationOn,
            label = "Suivi GPS en arrière-plan",
            explanation = "Active ou désactive le suivi en arrière-plan. Quand il est actif, l’application continue à vous suivre même si l’écran est verrouillé.",
        )
        HelpIconDetail(
            icon = Icons.Default.Visibility,
            label = "Indices de monuments",
            explanation = "Affiche ou masque les petits points des secteurs encore non découverts qui contiennent des monuments.",
        )
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
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
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
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

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
