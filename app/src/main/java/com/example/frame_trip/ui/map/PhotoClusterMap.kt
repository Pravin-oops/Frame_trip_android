package com.example.frame_trip.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frame_trip.data.PhotoNode
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import java.util.Locale

private const val MAX_ZOOM = 21f
private const val SINGLE_SPOT_ZOOM = 14f
private const val FRAME_LIBRARY_ANIMATION_MS = 1_000
private const val CLUSTER_ZOOM_ANIMATION_MS = 500

/**
 * Full-screen map that clusters [visiblePhotos].
 *
 * @param allPhotos the whole library, used once to frame the camera around every photo.
 * @param contentPadding keeps the map's own controls and camera framing clear of overlays.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun PhotoClusterMap(
    allPhotos: List<PhotoNode>,
    visiblePhotos: List<PhotoNode>,
    showMyLocation: Boolean,
    contentPadding: PaddingValues,
    onPhotoClick: (PhotoNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState = rememberCameraPositionState()
    val scope = rememberCoroutineScope()
    val boundsPaddingPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    var mapLoaded by remember { mutableStateOf(false) }
    var libraryFramed by rememberSaveable { mutableStateOf(false) }

    // Frame the whole library once, when the first photos arrive, rather than on every scrub. Bounds
    // updates need the map to be laid out, hence waiting for onMapLoaded.
    LaunchedEffect(mapLoaded, allPhotos) {
        if (!mapLoaded || libraryFramed || allPhotos.isEmpty()) return@LaunchedEffect
        libraryFramed = true
        cameraPositionState.animate(
            cameraUpdateToFit(allPhotos, boundsPaddingPx, singleSpotZoom = SINGLE_SPOT_ZOOM),
            FRAME_LIBRARY_ANIMATION_MS,
        )
    }

    // Marker content is rendered into bitmaps outside the theme's composition, so resolve colours here.
    val markerColor = MaterialTheme.colorScheme.primary
    val markerContentColor = MaterialTheme.colorScheme.onPrimary

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = showMyLocation, maxZoomPreference = MAX_ZOOM),
        uiSettings = MapUiSettings(
            mapToolbarEnabled = false,
            myLocationButtonEnabled = showMyLocation,
            zoomControlsEnabled = false,
        ),
        contentPadding = contentPadding,
        mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
        onMapLoaded = { mapLoaded = true },
    ) {
        Clustering(
            items = visiblePhotos,
            onClusterClick = { cluster ->
                val zoomedIn = (cameraPositionState.position.zoom + 3f).coerceAtMost(MAX_ZOOM)
                scope.launch {
                    cameraPositionState.animate(
                        cameraUpdateToFit(cluster.items, boundsPaddingPx, singleSpotZoom = zoomedIn),
                        CLUSTER_ZOOM_ANIMATION_MS,
                    )
                }
                true
            },
            onClusterItemClick = { photo ->
                onPhotoClick(photo)
                true
            },
            clusterContent = { cluster ->
                ClusterBubble(count = cluster.size, color = markerColor, contentColor = markerContentColor)
            },
            clusterItemContent = { PhotoDot(color = markerColor, ringColor = markerContentColor) },
            clusterContentAnchor = Offset(0.5f, 0.5f),
            clusterItemContentAnchor = Offset(0.5f, 0.5f),
        )
    }
}

/**
 * Camera update showing every photo in [photos], which must not be empty. When they all share one
 * spot (e.g. a burst), bounds would jump to the maximum zoom, so zoom to [singleSpotZoom] instead.
 */
private fun cameraUpdateToFit(photos: Collection<PhotoNode>, paddingPx: Int, singleSpotZoom: Float): CameraUpdate {
    val bounds = LatLngBounds.builder().apply { photos.forEach { include(it.latLng) } }.build()
    return if (bounds.northeast == bounds.southwest) {
        CameraUpdateFactory.newLatLngZoom(bounds.center, singleSpotZoom)
    } else {
        CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
    }
}

@Composable
private fun ClusterBubble(count: Int, color: Color, contentColor: Color) {
    val diameter = when {
        count < 10 -> 36.dp
        count < 100 -> 42.dp
        count < 1_000 -> 50.dp
        else -> 58.dp
    }
    // Translucent halo around a solid disc, so overlapping clusters stay distinguishable.
    Box(
        modifier = Modifier
            .size(diameter + 10.dp)
            .background(color.copy(alpha = 0.25f), CircleShape)
            .padding(5.dp)
            .background(color, CircleShape)
            .border(2.dp, contentColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = compactCount(count),
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PhotoDot(color: Color, ringColor: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(color, CircleShape)
            .border(3.dp, ringColor, CircleShape),
    )
}

/** 45 -> "45", 1234 -> "1.2k", 25300 -> "25k". */
private fun compactCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 9_950 -> String.format(Locale.ROOT, "%.1fk", count / 1_000f).replace(".0k", "k")
    else -> "${(count + 500) / 1_000}k"
}
