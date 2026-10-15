package com.example.frame_trip.data

import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem

/**
 * A camera photo that carries usable GPS coordinates.
 *
 * Implements [ClusterItem] directly so the filtered list can go straight to the clustering layer,
 * without allocating a wrapper per photo every time the timeline slider moves.
 *
 * @property timestamp when the photo was taken, in epoch milliseconds.
 */
data class PhotoNode(
    val id: Long,
    val uri: Uri,
    val timestamp: Long,
    val latLng: LatLng,
) : ClusterItem {
    override fun getPosition(): LatLng = latLng

    override fun getTitle(): String? = null

    override fun getSnippet(): String? = null

    override fun getZIndex(): Float? = null
}
