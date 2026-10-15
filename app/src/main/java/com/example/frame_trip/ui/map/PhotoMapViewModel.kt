package com.example.frame_trip.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frame_trip.data.PhotoNode
import com.example.frame_trip.data.PhotoRepository
import com.example.frame_trip.permissions.AppPermissions
import com.example.frame_trip.permissions.MediaAccess
import com.example.frame_trip.permissions.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update

class PhotoMapViewModel(
    private val repository: PhotoRepository,
    initialPermissions: PermissionState,
) : ViewModel() {

    private val permissions = MutableStateFlow(initialPermissions)
    private val mediaPermissionPermanentlyDenied = MutableStateFlow(false)
    private val rescanRequests = MutableStateFlow(0)

    /** Slider position. Starts at 1 ("now"), which also keeps newly taken photos in view. */
    private val timelineFraction = MutableStateFlow(1f)

    /** Key of the scan currently feeding [library]; see [libraryFor]. */
    private var currentScan: ScanKey? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val library: StateFlow<LibraryState> =
        combine(permissions, rescanRequests) { state, generation ->
            ScanKey(state.mediaAccess, state.mediaLocationGranted, generation)
        }
            .distinctUntilChanged()
            .flatMapLatest(::libraryFor)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                if (initialPermissions.canReadPhotos) LibraryState.Scanning else LibraryState.NoAccess,
            )

    private val selection: Flow<TimelineSelection> =
        combine(library, timelineFraction) { library, fraction ->
            TimelineSelection.of((library as? LibraryState.Ready)?.photos.orEmpty(), fraction)
        }

    /**
     * What the map clusters. Only re-emitted when the slider actually crosses a photo, and at most
     * every [MAP_UPDATE_INTERVAL_MS] so fast scrubbing through a large library does not re-cluster
     * on every frame. The final position is always delivered.
     */
    private val mapPhotos: Flow<List<PhotoNode>> = selection
        .distinctUntilChanged { old, new -> old.photos === new.photos && old.visibleCount == new.visibleCount }
        .map { it.visiblePhotos }
        .throttleLatest(MAP_UPDATE_INTERVAL_MS)

    val uiState: StateFlow<PhotoMapUiState> =
        combine(
            permissions,
            mediaPermissionPermanentlyDenied,
            library,
            selection,
            mapPhotos,
        ) { permissions, permanentlyDenied, library, selection, mapPhotos ->
            PhotoMapUiState(
                permissions = permissions,
                mediaPermissionPermanentlyDenied = permanentlyDenied,
                libraryStatus = library.status,
                allPhotos = selection.photos,
                visiblePhotos = mapPhotos,
                timeline = selection.toTimelineState(),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            PhotoMapUiState(permissions = initialPermissions, libraryStatus = library.value.status),
        )

    /** Call with the latest permission state, e.g. on every resume. */
    fun onPermissionsChanged(state: PermissionState) {
        permissions.value = state
        if (state.canReadPhotos) mediaPermissionPermanentlyDenied.value = false
        // Under partial access, changing the selection leaves the permission state untouched but
        // changes which images MediaStore returns, so query again.
        if (state.mediaAccess == MediaAccess.Partial) rescanRequests.update { it + 1 }
    }

    /**
     * @param canAskAgain whether the system will still show the photo permission dialog; false
     * after the user has denied it twice.
     */
    fun onMediaPermissionResult(state: PermissionState, canAskAgain: Boolean) {
        onPermissionsChanged(state)
        mediaPermissionPermanentlyDenied.value = !state.canReadPhotos && !canAskAgain
    }

    fun onTimelineScrub(fraction: Float) {
        timelineFraction.value = fraction.coerceIn(0f, 1f)
    }

    fun retry() {
        rescanRequests.update { it + 1 }
    }

    private fun libraryFor(key: ScanKey): Flow<LibraryState> {
        if (key.mediaAccess == MediaAccess.None) {
            currentScan = null
            return flowOf(LibraryState.NoAccess)
        }
        // Only blank the map when what the app can see has changed. Re-subscribing after the app was
        // in the background, or re-querying after a partial-access reselection, keeps the current
        // photos on screen until the fresh result replaces them.
        val accessChanged = currentScan?.hasSameAccessAs(key) != true
        currentScan = key
        return repository.observePhotoNodes()
            .map<List<PhotoNode>, LibraryState> { LibraryState.Ready(it) }
            .onStart { if (accessChanged) emit(LibraryState.Scanning) }
            .catch { error ->
                Log.e(TAG, "Photo scan failed", error)
                currentScan = null
                emit(LibraryState.Failed)
            }
    }

    private data class ScanKey(
        val mediaAccess: MediaAccess,
        val mediaLocationGranted: Boolean,
        val generation: Int,
    ) {
        fun hasSameAccessAs(other: ScanKey) =
            mediaAccess == other.mediaAccess && mediaLocationGranted == other.mediaLocationGranted
    }

    private sealed class LibraryState(val status: LibraryStatus) {
        data object NoAccess : LibraryState(LibraryStatus.NoAccess)
        data object Scanning : LibraryState(LibraryStatus.Scanning)
        data class Ready(val photos: List<PhotoNode>) : LibraryState(LibraryStatus.Ready)
        data object Failed : LibraryState(LibraryStatus.Failed)
    }

    /** Where the slider sits within [photos], which must be sorted oldest first. */
    private class TimelineSelection(
        val photos: List<PhotoNode>,
        val fraction: Float,
        val selectedMillis: Long,
        val visibleCount: Int,
    ) {
        /** A view onto [photos], so moving the slider never copies the list. */
        val visiblePhotos: List<PhotoNode>
            get() = if (visibleCount == photos.size) photos else photos.subList(0, visibleCount)

        fun toTimelineState(): TimelineState? {
            if (photos.isEmpty()) return null
            return TimelineState(
                startMillis = photos.first().timestamp,
                endMillis = photos.last().timestamp,
                selectedMillis = selectedMillis,
                fraction = fraction,
                visibleCount = visibleCount,
                totalCount = photos.size,
            )
        }

        companion object {
            fun of(photos: List<PhotoNode>, fraction: Float): TimelineSelection {
                if (photos.isEmpty()) return TimelineSelection(photos, fraction, 0L, 0)
                val selected = Timeline.timestampAt(fraction, photos.first().timestamp, photos.last().timestamp)
                return TimelineSelection(photos, fraction, selected, photos.countTakenUpTo(selected))
            }
        }
    }

    companion object {
        private const val TAG = "PhotoMapViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MAP_UPDATE_INTERVAL_MS = 80L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                PhotoMapViewModel(
                    repository = PhotoRepository(application),
                    initialPermissions = AppPermissions.currentState(application),
                )
            }
        }
    }
}

/** Emits the first value immediately, then at most one (the latest) value per [periodMillis]. */
private fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> =
    conflate().transform {
        emit(it)
        delay(periodMillis)
    }
