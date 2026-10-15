package com.example.frame_trip.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** How much of the photo library the user has shared with the app. */
enum class MediaAccess {
    /** Nothing granted yet, or denied. */
    None,

    /** Android 14+ "Select photos": only the images the user picked are visible. */
    Partial,

    /** READ_MEDIA_IMAGES granted: the whole library is visible. */
    Full,
}

data class PermissionState(
    val mediaAccess: MediaAccess = MediaAccess.None,
    /** ACCESS_MEDIA_LOCATION: without it the platform strips GPS tags from photo EXIF data. */
    val mediaLocationGranted: Boolean = false,
    /** Fine or coarse location: only used for the "my location" layer on the map. */
    val deviceLocationGranted: Boolean = false,
) {
    val canReadPhotos: Boolean get() = mediaAccess != MediaAccess.None
}

object AppPermissions {
    /**
     * Requested together. Including READ_MEDIA_VISUAL_USER_SELECTED lets the system offer the
     * "Select photos" option, and ACCESS_MEDIA_LOCATION unlocks unredacted EXIF locations.
     */
    val mediaPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

    /** Requested on demand, only when the user asks to see their own position. */
    val deviceLocationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun currentState(context: Context): PermissionState {
        fun isGranted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        return PermissionState(
            mediaAccess = when {
                isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.Full
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
                else -> MediaAccess.None
            },
            mediaLocationGranted = isGranted(Manifest.permission.ACCESS_MEDIA_LOCATION),
            deviceLocationGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
}
