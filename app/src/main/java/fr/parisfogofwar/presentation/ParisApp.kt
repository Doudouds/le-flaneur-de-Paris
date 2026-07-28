package fr.parisfogofwar.presentation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.parisfogofwar.presentation.collection.CollectionScreen
import fr.parisfogofwar.presentation.map.MapScreen
import fr.parisfogofwar.presentation.settings.SettingsScreen
import fr.parisfogofwar.presentation.statistics.StatisticsScreen

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ParisApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val destinations = listOf(
        Destination("map", "Carte", Icons.Default.Map),
        Destination("collection", "Collection", Icons.Default.CollectionsBookmark),
        Destination("statistics", "Stats", Icons.Default.BarChart),
        Destination("settings", "Réglages", Icons.Default.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigate(destination.route) { launchSingleTop = true; restoreState = true } },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "map", modifier = Modifier.padding(padding)) {
            composable("map") { MapScreen(state, viewModel) }
            composable("collection") { CollectionScreen(state.revealedMonuments) }
            composable("statistics") { StatisticsScreen(state.statistics) }
            composable("settings") { SettingsScreen(state.statistics, viewModel) }
        }
    }
}

@Composable
fun LocationPermissionLauncher(viewModel: AppViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.setPermissionState(if (granted) LocationPermissionState.Granted else LocationPermissionState.Denied)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.setPermissionState(LocationPermissionState.Granted)
    }
    Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) {
        Text("Autoriser la localisation")
    }
}
