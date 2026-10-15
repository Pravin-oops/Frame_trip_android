package com.example.frame_trip.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads geotagged photos from the device camera folder.
 *
 * Since Android 10 the MediaStore LATITUDE/LONGITUDE columns are always null, so the coordinates
 * come from each file's EXIF header, opened via [MediaStore.setRequireOriginal] so they are not
 * redacted. Results are cached by media id + DATE_MODIFIED, which makes the re-scans triggered by
 * library changes cheap: only new or edited files are opened again.
 *
 * @param relativePathPrefix MediaStore RELATIVE_PATH prefix of the folder to scan.
 */
class PhotoRepository(
    context: Context,
    private val relativePathPrefix: String = CAMERA_RELATIVE_PATH,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val locationCache = ConcurrentHashMap<Long, CachedLocation>()

    /**
     * Emits every geotagged photo in the camera folder, oldest first, and emits again whenever the
     * image collection changes. Photos without usable GPS data are left out.
     *
     * Requires READ_MEDIA_IMAGES or READ_MEDIA_VISUAL_USER_SELECTED. Without ACCESS_MEDIA_LOCATION
     * the platform redacts EXIF locations, so the list will be empty.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observePhotoNodes(): Flow<List<PhotoNode>> =
        imageCollectionChanges()
            .debounce(CHANGE_DEBOUNCE_MS)
            .onStart { emit(Unit) }
            .mapLatest { scanPhotos() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    private suspend fun scanPhotos(): List<PhotoNode> = coroutineScope {
        val canReadOriginal = appContext.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val rows = queryRows()
        // Forget photos that were deleted since the previous scan.
        locationCache.keys.retainAll(rows.mapTo(HashSet(rows.size)) { it.id })

        val exifPermits = Semaphore(EXIF_PARALLELISM)
        rows.map { row -> async { exifPermits.withPermit { row.toPhotoNode(canReadOriginal) } } }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.timestamp }
    }

    private fun queryRows(): List<MediaRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$relativePathPrefix%")

        val rows = ArrayList<MediaRow>()
        try {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    // getLong() returns 0 for NULL, which MediaRow.timestamp treats as "missing".
                    rows += MediaRow(
                        id = cursor.getLong(idColumn),
                        dateTakenMillis = cursor.getLong(takenColumn),
                        dateModifiedSeconds = cursor.getLong(modifiedColumn),
                        dateAddedSeconds = cursor.getLong(addedColumn),
                    )
                }
            }
        } catch (e: SecurityException) {
            // Access was revoked between the permission check and the query.
            Log.w(TAG, "MediaStore query denied", e)
        }
        return rows
    }

    private fun MediaRow.toPhotoNode(canReadOriginal: Boolean): PhotoNode? {
        val uri = ContentUris.withAppendedId(collection, id)
        val cached = locationCache[id]
        val latLng = if (cached != null && cached.dateModifiedSeconds == dateModifiedSeconds &&
            cached.fromOriginal == canReadOriginal
        ) {
            cached.latLng
        } else {
            readExifLatLng(uri, canReadOriginal).also {
                locationCache[id] = CachedLocation(dateModifiedSeconds, canReadOriginal, it)
            }
        }
        return latLng?.let { PhotoNode(id = id, uri = uri, timestamp = timestamp, latLng = it) }
    }

    private fun readExifLatLng(uri: Uri, requireOriginal: Boolean): LatLng? {
        // Without the original, MediaProvider serves a copy with the GPS tags stripped. Asking for
        // it without ACCESS_MEDIA_LOCATION throws, so only do so when the permission is held.
        val source = if (requireOriginal) MediaStore.setRequireOriginal(uri) else uri
        return try {
            resolver.openFileDescriptor(source, "r")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).latLong
            }?.let { (latitude, longitude) -> validLatLngOrNull(latitude, longitude) }
        } catch (e: Exception) {
            // A corrupt or unsupported file, or access revoked mid-scan, must not abort the scan.
            Log.d(TAG, "No readable EXIF location for $uri", e)
            null
        }
    }

    private fun validLatLngOrNull(latitude: Double, longitude: Double): LatLng? = when {
        // Range checks also reject NaN and infinities.
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> null
        // (0, 0) is what redacted files and cameras without a GPS fix write.
        latitude == 0.0 && longitude == 0.0 -> null
        else -> LatLng(latitude, longitude)
    }

    private fun imageCollectionChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private class MediaRow(
        val id: Long,
        val dateTakenMillis: Long,
        val dateModifiedSeconds: Long,
        val dateAddedSeconds: Long,
    ) {
        /** DATE_TAKEN comes from EXIF and can be missing; fall back to the file timestamps. */
        val timestamp: Long
            get() = when {
                dateTakenMillis > 0 -> dateTakenMillis
                dateModifiedSeconds > 0 -> dateModifiedSeconds * 1_000
                else -> dateAddedSeconds * 1_000
            }
    }

    /** [latLng] is null for photos already known to have no GPS data, so they are not re-read. */
    private class CachedLocation(
        val dateModifiedSeconds: Long,
        val fromOriginal: Boolean,
        val latLng: LatLng?,
    )

    private companion object {
        const val TAG = "PhotoRepository"
        const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"
        const val EXIF_PARALLELISM = 8
        const val CHANGE_DEBOUNCE_MS = 1_000L
    }
}
