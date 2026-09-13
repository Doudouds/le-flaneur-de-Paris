package fr.parisfogofwar.presentation.map

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.map.MapConfig
import fr.parisfogofwar.presentation.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val renderer = remember { MapRenderer() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var selectedMonumentId by remember { mutableStateOf<String?>(null) }
    val selectedMonument = selectedMonumentId?.let { id -> state.revealedMonuments.firstOrNull { it.id == id } }
    var showWelcome by remember { mutableStateOf(false) }
    var showMonumentHints by remember { mutableStateOf(false) }
    var initialCameraApplied by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("onboarding", 0) }
    val renderScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val latestState by rememberUpdatedState(state)
    val latestHints by rememberUpdatedState(showMonumentHints)
    val latestRevealedMonuments by rememberUpdatedState(state.revealedMonuments)

    DisposableEffect(mapView, lifecycleOwner) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.onStart()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_STOP -> view?.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(Unit) { showWelcome = !preferences.getBoolean("seen", false) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.openMonumentId, state.revealedMonuments) {
        val id = state.openMonumentId ?: return@LaunchedEffect
        if (state.revealedMonuments.any { it.id == id }) {
            selectedMonumentId = id
            viewModel.clearOpenMonument()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                MapLibre.getInstance(it)
                MapView(it, MapLibreMapOptions.createFromAttributes(it).textureMode(true)).also { view ->
                    view.onCreate(null)
                    mapView = view
                    view.getMapAsync { mapInstance ->
                        if (mapView !== view) return@getMapAsync
                        map = mapInstance
                        // The raster layer below supplies the map; no remote demo style is needed.
                        mapInstance.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[]}""")) { style ->
                            if (mapView !== view) return@setStyle
                            val parisBoundaryJson = it.assets.open("paris_boundary.geojson").bufferedReader().use { reader -> reader.readText() }
                            renderer.ensureLayers(style, parisBoundaryJson)
                            val initialFocus = latestState.focusLocation
                            val initialLocation = initialFocus ?: latestState.currentLocation
                            mapInstance.cameraPosition = CameraPosition.Builder()
                                .target(MapLatLng(initialLocation?.latitude ?: MapConfig.parisCenter.latitude, initialLocation?.longitude ?: MapConfig.parisCenter.longitude))
                                .zoom(when {
                                    initialFocus != null -> 17.0
                                    initialLocation != null -> 15.5
                                    else -> 11.4
                                })
                                .build()
                            mapReady = true
                            renderer.update(style, latestState, latestHints, renderScope)
                            mapInstance.addOnMapLongClickListener { point ->
                                viewModel.simulateDiscovery(LatLng(point.latitude, point.longitude))
                                true
                            }
                            mapInstance.addOnMapClickListener { point ->
                                val screenPoint = mapInstance.projection.toScreenLocation(point)
                                val touchRadiusPx = 24f * context.resources.displayMetrics.density
                                val features = mapInstance.queryRenderedFeatures(
                                    RectF(
                                        screenPoint.x - touchRadiusPx,
                                        screenPoint.y - touchRadiusPx,
                                        screenPoint.x + touchRadiusPx,
                                        screenPoint.y + touchRadiusPx,
                                    ),
                                    MapConfig.MONUMENT_LAYER_ID,
                                )
                                val id = features.firstOrNull()?.getProperty("id")?.asString
                                selectedMonumentId = latestRevealedMonuments.firstOrNull { it.id == id }?.id
                                selectedMonumentId != null
                            }
                        }
                    }
                }
            },
            onRelease = { view ->
                renderer.dispose()
                mapReady = false
                map = null
                view.onPause()
                view.onStop()
                view.onDestroy()
                mapView = null
            },
            update = { _ ->
                if (mapReady) {
                    map?.style?.let { style ->
                        renderer.update(style, state, showMonumentHints, renderScope)
                    }
                }
            },
        )

        if (BuildConfig.DEBUG && state.showLocationDebug) {
            state.lastLocationUpdate?.let { update ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("GPS DEBUG", style = MaterialTheme.typography.labelMedium)
                        Text("brute: %.6f, %.6f".format(update.raw.coordinates.latitude, update.raw.coordinates.longitude), style = MaterialTheme.typography.labelSmall)
                        Text("filtrée: ${update.filtered?.coordinates ?: "—"}", style = MaterialTheme.typography.labelSmall)
                        Text("±%.1f m · âge %d ms · ${update.decision}".format(update.raw.accuracyMeters, update.ageMillis), style = MaterialTheme.typography.labelSmall)
                        Text("vitesse ${update.raw.speedMetersPerSecond ?: update.estimatedSpeedMetersPerSecond ?: "—"} · ${update.quality}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        LaunchedEffect(mapReady, state.currentLocation, state.focusLocation) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!mapReady) return@LaunchedEffect
            val focusLocation = state.focusLocation
            if (focusLocation != null) {
                mapInstance.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(MapLatLng(focusLocation.latitude, focusLocation.longitude))
                    .zoom(17.0)
                    .build()))
                initialCameraApplied = true
                viewModel.clearFocusLocation()
            } else if (!initialCameraApplied) {
                state.currentLocation?.let { location ->
                    mapInstance.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                        .target(MapLatLng(location.latitude, location.longitude))
                        .zoom(15.5)
                        .build()))
                    initialCameraApplied = true
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("Secteurs : ${state.statistics.discoveredCells} / ${state.statistics.totalCells}", Modifier.weight(1f))
                StatPill("Monuments : ${state.statistics.revealedMonuments} / ${state.statistics.totalMonuments}", Modifier.weight(1f))
            }
            if (state.permissionState != LocationPermissionState.Granted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Explorez Paris", style = MaterialTheme.typography.titleMedium)
                        Text("Votre position sert à dévoiler la carte lorsque l'application est ouverte. Vous pouvez activer le suivi en arrière-plan dans Réglages.")
                        LocationPermissionLauncher(viewModel)
                    }
                }
            } else if (state.currentLocation == null) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Recherche de votre position…", Modifier.padding(12.dp))
                }
            } else if (state.locationPrecision == LocationPrecision.Approximate) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Localisation approximative : activez la position précise dans les réglages Android pour une trace et un fog of war plus fiables.", Modifier.padding(12.dp))
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = viewModel::toggleBackgroundTrackingFromMap,
                containerColor = if (state.backgroundTrackingEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ) {
                Icon(
                    if (state.backgroundTrackingEnabled) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = if (state.backgroundTrackingEnabled) "Désactiver le suivi en arrière-plan" else "Activer le suivi en arrière-plan",
                )
            }
            SmallFloatingActionButton(
                onClick = {
                    showMonumentHints = !showMonumentHints
                    renderScope.launch {
                        snackbarHostState.showSnackbar(
                            if (showMonumentHints) {
                                "Les indices des monuments restant à découvrir sont visibles sur la carte."
                            } else {
                                "Les indices des monuments restant à découvrir sont masqués sur la carte."
                            },
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                containerColor = if (showMonumentHints) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ) {
                Icon(
                    if (showMonumentHints) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (showMonumentHints) "Masquer les zones de monuments" else "Afficher les zones de monuments",
                )
            }
            SmallFloatingActionButton(
                onClick = {
                    map?.let { currentMap ->
                        currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                            .target(currentMap.cameraPosition.target)
                            .zoom((currentMap.cameraPosition.zoom + 1.0).coerceAtMost(22.0))
                            .build()))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { Icon(Icons.Default.ZoomIn, contentDescription = "Zoomer") }
            SmallFloatingActionButton(
                onClick = {
                    map?.let { currentMap ->
                        currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                            .target(currentMap.cameraPosition.target)
                            .zoom((currentMap.cameraPosition.zoom - 1.0).coerceAtLeast(1.0))
                            .build()))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { Icon(Icons.Default.ZoomOut, contentDescription = "Dézoomer") }
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = { showWelcome = false; preferences.edit().putBoolean("seen", true).apply() },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(fr.parisfogofwar.R.drawable.logo),
                        contentDescription = "Logo Le Flâneur de Paris",
                        modifier = Modifier.size(128.dp),
                    )
                    Text("Bienvenue dans Le Flâneur de Paris")
                }
            },
            text = { Text("Déplacez-vous dans Paris pour dévoiler des secteurs et révéler les monuments historiques. La progression reste sur votre téléphone. Le suivi en arrière-plan peut être activé dans Réglages, avec une notification permanente.") },
            confirmButton = {
                TextButton(onClick = { showWelcome = false; preferences.edit().putBoolean("seen", true).apply() }) { Text("Compris") }
            },
        )
    }

    selectedMonument?.let { monument ->
        LaunchedEffect(monument.id, monument.imageUrl) { viewModel.ensureMonumentImage(monument) }
        MonumentDetailsBottomSheet(
            monument = monument,
            onDismiss = { selectedMonumentId = null },
            onLocate = {
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(MapLatLng(monument.latitude, monument.longitude))
                    .zoom(17.0)
                    .build()))
                selectedMonumentId = null
            },
        )
    }
}

@Composable
private fun StatPill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), tonalElevation = 4.dp) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

private class MapRenderer {
    private data class FogRenderKey(
        val discoveredCells: Set<GridCellId>,
    )

    private var lastFogRenderKey: FogRenderKey? = null
    private var fogRenderJob: Job? = null

    private var lastMonuments: List<Monument>? = null
    private var lastHints: Pair<Set<GridCellId>, Set<GridCellId>>? = null
    private var lastLocation: LatLng? = null

    fun dispose() {
        fogRenderJob?.cancel()
    }

    fun ensureLayers(style: Style, parisBoundaryJson: String) {
        lastFogRenderKey = null
        parisBoundaryRing = Polygon.fromJson(parisBoundaryJson).coordinates().firstOrNull().orEmpty()
        if (style.getSource(MapConfig.OSM_SOURCE_ID) == null) {
            style.addSource(RasterSource(MapConfig.OSM_SOURCE_ID, TileSet("osm", MapConfig.OSM_TILE_URL), 256))
        }
        if (style.getLayer(MapConfig.OSM_LAYER_ID) == null) {
            style.addLayer(RasterLayer(MapConfig.OSM_LAYER_ID, MapConfig.OSM_SOURCE_ID).withProperties(rasterOpacity(1f)))
        }
        if (style.getSource(MapConfig.FOG_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.FOG_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.FOG_LAYER_ID) == null) style.addLayer(FillLayer(MapConfig.FOG_LAYER_ID, MapConfig.FOG_SOURCE_ID).withProperties(fillColor(Color.parseColor("#172033")), fillOpacity(1f)))
        if (style.getLayer(MapConfig.FOG_BORDER_LAYER_ID) == null) style.addLayer(LineLayer(MapConfig.FOG_BORDER_LAYER_ID, MapConfig.FOG_SOURCE_ID).withProperties(lineColor(Color.parseColor("#52627d")), lineWidth(0.5f), lineOpacity(.25f)))
        if (style.getSource(MapConfig.MONUMENT_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.MONUMENT_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.MONUMENT_LAYER_ID) == null) style.addLayer(
            CircleLayer(MapConfig.MONUMENT_LAYER_ID, MapConfig.MONUMENT_SOURCE_ID).withProperties(
                circleColor(
                    match(
                        get("stars"),
                        literal(1), color(Color.parseColor("#f7b955")),
                        literal(2), color(Color.parseColor("#64b5f6")),
                        literal(3), color(Color.parseColor("#8e3a8a")),
                        color(Color.parseColor("#f7b955")),
                    ),
                ),
                circleRadius(6f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(1.5f),
            ),
        )
        if (style.getSource(MapConfig.MONUMENT_HINT_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.MONUMENT_HINT_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.MONUMENT_HINT_LAYER_ID) == null) style.addLayer(CircleLayer(MapConfig.MONUMENT_HINT_LAYER_ID, MapConfig.MONUMENT_HINT_SOURCE_ID).withProperties(circleColor(Color.parseColor("#4caf50")), circleRadius(4f), circleOpacity(.75f), circleStrokeColor(Color.WHITE), circleStrokeWidth(1f)))
        if (style.getSource(MapConfig.LOCATION_SOURCE_ID) == null) style.addSource(GeoJsonSource(MapConfig.LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        if (style.getLayer(MapConfig.LOCATION_LAYER_ID) == null) style.addLayer(CircleLayer(MapConfig.LOCATION_LAYER_ID, MapConfig.LOCATION_SOURCE_ID).withProperties(circleColor(Color.parseColor("#3277e8")), circleRadius(7f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
    }

    fun update(style: Style, state: AppUiState, showMonumentHints: Boolean, scope: CoroutineScope) {
        updateSources(style, state, showMonumentHints, scope)
    }

    private fun updateSources(style: Style, state: AppUiState, showMonumentHints: Boolean, scope: CoroutineScope) {
        val fogSource = style.getSourceAs<GeoJsonSource>(MapConfig.FOG_SOURCE_ID) ?: return
        val monumentSource = style.getSourceAs<GeoJsonSource>(MapConfig.MONUMENT_SOURCE_ID) ?: return
        val monumentHintSource = style.getSourceAs<GeoJsonSource>(MapConfig.MONUMENT_HINT_SOURCE_ID) ?: return
        val locationSource = style.getSourceAs<GeoJsonSource>(MapConfig.LOCATION_SOURCE_ID) ?: return

        val renderKey = FogRenderKey(state.discoveredCells)
        if (renderKey != lastFogRenderKey && parisBoundaryRing.isNotEmpty()) {
            lastFogRenderKey = renderKey
            fogRenderJob?.cancel()
            fogRenderJob = scope.launch(Dispatchers.Default) {
                // The Paris boundary is the outer ring. Discovered 200 m cells are
                // holes, so the fog cannot cover neighboring municipalities.
                val rings = buildList {
                    add(parisBoundaryRing)
                    state.discoveredCells.forEach { cell ->
                        ensureActive()
                        val points = cell.toWebMercatorBounds().toWgs84Polygon().map { Point.fromLngLat(it.longitude, it.latitude) }
                        add(points)
                    }
                }
                val fogFeature = Feature.fromGeometry(Polygon.fromLngLats(rings))
                val fogJson = FeatureCollection.fromFeatures(arrayOf(fogFeature)).toJson()
                withContext(Dispatchers.Main.immediate) {
                    if (lastFogRenderKey == renderKey) {
                        fogSource.setGeoJson(fogJson)
                    }
                }
            }
        }
        if (state.revealedMonuments != lastMonuments) {
            lastMonuments = state.revealedMonuments
            val monumentFeatures = state.revealedMonuments.map { monument ->
                Feature.fromGeometry(Point.fromLngLat(monument.longitude, monument.latitude)).also { feature ->
                    feature.addStringProperty("id", monument.id)
                    feature.addNumberProperty("stars", monument.stars ?: 1)
                }
            }
            monumentSource.setGeoJson(FeatureCollection.fromFeatures(monumentFeatures))
        }
        val hints = if (showMonumentHints) state.allMonumentCells to state.discoveredCells else emptySet<GridCellId>() to emptySet<GridCellId>()
        if (hints != lastHints) {
            lastHints = hints
            val monumentHintFeatures = if (showMonumentHints) {
                state.allMonumentCells.filter { it !in state.discoveredCells }.map { cell ->
                    val bounds = cell.toWebMercatorBounds()
                    val center = MercatorPoint(
                        (bounds.minX + bounds.maxX) / 2.0,
                        (bounds.minY + bounds.maxY) / 2.0,
                    ).toWgs84()
                    Feature.fromGeometry(Point.fromLngLat(center.longitude, center.latitude))
                }
            } else emptyList()
            monumentHintSource.setGeoJson(FeatureCollection.fromFeatures(monumentHintFeatures))
        }
        val location = state.currentLocation
        if (location != lastLocation) {
            lastLocation = location
            val locationFeatures: List<Feature> = if (location == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
            locationSource.setGeoJson(FeatureCollection.fromFeatures(locationFeatures))
        }
    }

    private var parisBoundaryRing: List<Point> = emptyList()
}
