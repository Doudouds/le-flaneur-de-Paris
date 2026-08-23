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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import fr.parisfogofwar.notifications.DiscoveryNotificationHelper
import fr.parisfogofwar.notifications.DiscoveryNotificationLevel
import fr.parisfogofwar.presentation.AppViewModel
import androidx.compose.material3.LinearProgressIndicator

@Composable
fun SettingsScreen(statistics: ExplorationStatistics, viewModel: AppViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    val appState by viewModel.uiState.collectAsStateWithLifecycle()
    val offlineMapState by viewModel.offlineMapState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    var notificationLevel by remember { mutableStateOf(DiscoveryNotificationHelper.notificationLevel(context)) }
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
        Text("Niveau de notification", style = MaterialTheme.typography.titleMedium)
        Text("Choisissez les découvertes pour lesquelles vous souhaitez être averti.", style = MaterialTheme.typography.bodySmall)
        DiscoveryNotificationLevel.entries.forEach { level ->
            NotificationLevelChoice(
                level = level,
                selected = notificationLevel == level,
                onClick = {
                    notificationLevel = level
                    DiscoveryNotificationHelper.setNotificationLevel(context, level)
                },
            )
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
private fun NotificationLevelChoice(
    level: DiscoveryNotificationLevel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(level.label, style = MaterialTheme.typography.labelLarge)
            Text(level.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
