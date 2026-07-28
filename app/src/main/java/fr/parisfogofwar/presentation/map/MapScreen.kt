package fr.parisfogofwar.presentation.map

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.map.MapConfig
import fr.parisfogofwar.presentation.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var selectedMonument by remember { mutableStateOf<Monument?>(null) }
    var showWelcome by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("onboarding", 0) }

    LaunchedEffect(Unit) { showWelcome = !preferences.getBoolean("seen", false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                MapLibre.getInstance(it)
                MapView(it).also { view ->
                    view.onCreate(null)
                    view.getMapAsync { mapInstance ->
                        map = mapInstance
                        mapInstance.setStyle(Style.Builder().fromUri(MapConfig.styleUrl)) { style ->
                            MapRenderer.ensureLayers(style)
                            mapInstance.cameraPosition = CameraPosition.Builder().target(MapLatLng(MapConfig.parisCenter.latitude, MapConfig.parisCenter.longitude)).zoom(11.4).build()
                            mapInstance.addOnMapLongClickListener { point ->
                                viewModel.simulateDiscovery(LatLng(point.latitude, point.longitude))
                                true
                            }
                            mapInstance.addOnMapClickListener { point ->
                                val features = mapInstance.queryRenderedFeatures(mapInstance.projection.toScreenLocation(point), MapConfig.MONUMENT_LAYER_ID)
                                val id = features.firstOrNull()?.getProperty("id")?.asString
                                selectedMonument = state.revealedMonuments.firstOrNull { it.id == id }
                                selectedMonument != null
                            }
                        }
                    }
                }
            },
            update = { view ->
                map?.style?.let { style ->
                    MapRenderer.update(style, state, view)
                }
            },
        )

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("${state.statistics.discoveredCells} secteurs", Modifier.weight(1f))
                StatPill("${state.statistics.revealedMonuments} monuments", Modifier.weight(1f))
            }
            if (state.permissionState != LocationPermissionState.Granted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Explorez Paris", style = MaterialTheme.typography.titleMedium)
                        Text("Votre position sert uniquement à dévoiler la carte lorsque l’application est ouverte.")
                        LocationPermissionLauncher(viewModel)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                state.currentLocation?.let { location ->
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(MapLatLng(location.latitude, location.longitude)).zoom(15.5).build()))
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.MyLocation, contentDescription = "Recentrer sur ma position") }

        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("© OpenStreetMap contributors", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }

        state.message?.let { message ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } }) { Text(message) }
        }
    }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = { showWelcome = false; preferences.edit().putBoolean("seen", true).apply() },
            title = { Text("Bienvenue dans Paris Fog of War") },
            text = { Text("Déplacez-vous dans Paris pour dévoiler des secteurs et révéler les monuments historiques. La progression reste sur votre téléphone et le suivi ne fonctionne que lorsque l’application est ouverte.") },
            confirmButton = {
                TextButton(onClick = { showWelcome = false; preferences.edit().putBoolean("seen", true).apply() }) { Text("Compris") }
            },
        )
    }

    selectedMonument?.let { monument ->
        ModalBottomSheet(onDismissRequest = { selectedMonument = null }) {
            Column(Modifier.padding(24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(monument.name, style = MaterialTheme.typography.headlineSmall)
                monument.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                monument.description?.let { Text(it) }
                monument.merimeeReference?.let { Text("Référence Mérimée : $it") }
                monument.osmId?.let { Text("OpenStreetMap : $it") }
                Text("${monument.latitude}, ${monument.longitude}")
                TextButton(onClick = {
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(MapLatLng(monument.latitude, monument.longitude)).zoom(17.0).build()))
                    selectedMonument = null
                }) { Text("Centrer sur le monument") }
            }
        }
    }
}

@Composable
private fun StatPill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), tonalElevation = 4.dp) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

private object MapRenderer {
    fun ensureLayers(style: Style) {
        if (style.getSource(MapConfig.FOG_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.FOG_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.FOG_LAYER_ID) == null) style.addLayer(FillLayer(MapConfig.FOG_LAYER_ID, MapConfig.FOG_SOURCE_ID).withProperties(fillColor(Color.parseColor("#172033")), fillOpacity(.89f)))
        if (style.getLayer(MapConfig.FOG_BORDER_LAYER_ID) == null) style.addLayer(LineLayer(MapConfig.FOG_BORDER_LAYER_ID, MapConfig.FOG_SOURCE_ID).withProperties(lineColor(Color.parseColor("#52627d")), lineWidth(0.5f), lineOpacity(.35f)))
        if (style.getSource(MapConfig.MONUMENT_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.MONUMENT_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.MONUMENT_LAYER_ID) == null) style.addLayer(CircleLayer(MapConfig.MONUMENT_LAYER_ID, MapConfig.MONUMENT_SOURCE_ID).withProperties(circleColor(Color.parseColor("#f7b955")), circleRadius(6f), circleStrokeColor(Color.WHITE), circleStrokeWidth(1.5f)))
        if (style.getSource(MapConfig.LOCATION_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.LOCATION_LAYER_ID) == null) style.addLayer(CircleLayer(MapConfig.LOCATION_LAYER_ID, MapConfig.LOCATION_SOURCE_ID).withProperties(circleColor(Color.parseColor("#3277e8")), circleRadius(7f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
    }

    fun update(style: Style, state: AppUiState, mapView: MapView) {
        updateSources(style, state, mapView)
    }

    private fun updateSources(style: Style, state: AppUiState, mapView: MapView) {
        val fogSource = style.getSourceAs<GeoJsonSource>(MapConfig.FOG_SOURCE_ID) ?: return
        val monumentSource = style.getSourceAs<GeoJsonSource>(MapConfig.MONUMENT_SOURCE_ID) ?: return
        val locationSource = style.getSourceAs<GeoJsonSource>(MapConfig.LOCATION_SOURCE_ID) ?: return
        // The initial Paris viewport is deliberately bounded; subsequent camera moves are handled by the same source update.
        val area = GameArea()
        val center = MapConfig.parisCenter.toGridCellId()
        val cells = sequence {
            for (y in center.y - 70..center.y + 70) for (x in center.x - 120..center.x + 120) yield(GridCellId(x, y))
        }.filter(area::contains).filterNot(state.discoveredCells::contains)
        fogSource.setGeoJson(FeatureCollection.fromFeatures(cells.map { cell ->
            val points = cell.toWebMercatorBounds().toWgs84Polygon().map { Point.fromLngLat(it.longitude, it.latitude) }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
        }.toList().toTypedArray()))
        val monumentFeatures = state.revealedMonuments.map { monument ->
            Feature.fromGeometry(Point.fromLngLat(monument.longitude, monument.latitude)).also { feature -> feature.addStringProperty("id", monument.id) }
        }
        monumentSource.setGeoJson(FeatureCollection.fromFeatures(monumentFeatures))
        val location = state.currentLocation
        val locationFeatures: List<Feature> = if (location == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
        locationSource.setGeoJson(FeatureCollection.fromFeatures(locationFeatures))
    }
}
