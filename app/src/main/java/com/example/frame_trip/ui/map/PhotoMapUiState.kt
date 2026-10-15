package com.example.frame_trip.ui.map

import com.example.frame_trip.data.PhotoNode
import com.example.frame_trip.permissions.PermissionState

enum class LibraryStatus {
    /** No photo permission, so nothing has been scanned. */
    NoAccess,

    /** First scan for the current access level is running. */
    Scanning,

    Ready,

    Failed,
}

data class PhotoMapUiState(
    val permissions: PermissionState = PermissionState(),
    /** The system will no longer show the photo permission dialog; only Settings can grant it. */
    val mediaPermissionPermanentlyDenied: Boolean = false,
    val libraryStatus: LibraryStatus = LibraryStatus.NoAccess,
    /** Every geotagged photo, oldest first. */
    val allPhotos: List<PhotoNode> = emptyList(),
    /** Photos taken up to the slider position: the set the map clusters. */
    val visiblePhotos: List<PhotoNode> = emptyList(),
    /** Null while there are no photos to scrub through. */
    val timeline: TimelineState? = null,
)

data class TimelineState(
    val startMillis: Long,
    val endMillis: Long,
    val selectedMillis: Long,
    /** Slider position in 0..1 between [startMillis] and [endMillis]. */
    val fraction: Float,
    val visibleCount: Int,
    val totalCount: Int,
) {
    /** False when every photo shares one timestamp (e.g. a single photo). */
    val isScrubbable: Boolean get() = endMillis > startMillis
}
