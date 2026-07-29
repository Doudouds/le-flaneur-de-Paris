package fr.parisfogofwar.map

import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.LatLng

object MapConfig {
    const val FOG_SOURCE_ID = "fog-source"
    const val FOG_LAYER_ID = "fog-layer"
    const val FOG_BORDER_LAYER_ID = "fog-border-layer"
    const val MONUMENT_SOURCE_ID = "monuments-source"
    const val MONUMENT_LAYER_ID = "monuments-layer"
    const val LOCATION_SOURCE_ID = "location-source"
    const val LOCATION_LAYER_ID = "location-layer"
    const val OSM_SOURCE_ID = "osm-raster-source"
    const val OSM_LAYER_ID = "osm-raster-layer"
    const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    val parisCenter = LatLng(48.8566, 2.3522)
    val styleUrl: String get() = BuildConfig.MAP_STYLE_URL
}
