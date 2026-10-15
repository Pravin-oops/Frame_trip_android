package com.example.frame_trip.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frame_trip.R
import com.example.frame_trip.data.PhotoNode
import com.example.frame_trip.permissions.AppPermissions
import com.example.frame_trip.permissions.MediaAccess

/** Wires [PhotoMapScreen] to the ViewModel and to the runtime permission dialogs. */
@Composable
fun PhotoMapRoute(viewModel: PhotoMapViewModel = viewModel(factory = PhotoMapViewModel.Factory)) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // The rationale flag turns true after a first denial. If it is still false after a denial,
        // the system will not show the dialog again and only Settings can grant access.
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) ?: true
        viewModel.onMediaPermissionResult(AppPermissions.currentState(context), canAskAgain)
    }

    var locationRequested by rememberSaveable { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationRequested = true
        viewModel.onPermissionsChanged(AppPermissions.currentState(context))
    }

    // Access can change while the app is in the background (Settings, the system photo picker).
    LifecycleResumeEffect(viewModel) {
        viewModel.onPermissionsChanged(AppPermissions.currentState(context))
        onPauseOrDispose { }
    }

    // Ask once on first launch; after that the permission screen offers a button.
    var requestedOnLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!requestedOnLaunch && !uiState.permissions.canReadPhotos) {
            requestedOnLaunch = true
            mediaPermissionLauncher.launch(AppPermissions.mediaPermissions)
        }
    }

    PhotoMapScreen(
        uiState = uiState,
        showLocationPrompt = !uiState.permissions.deviceLocationGranted && !locationRequested,
        onRequestMediaAccess = { mediaPermissionLauncher.launch(AppPermissions.mediaPermissions) },
        onRequestDeviceLocation = { locationPermissionLauncher.launch(AppPermissions.deviceLocationPermissions) },
        onOpenSettings = context::openAppSettings,
        onTimelineScrub = viewModel::onTimelineScrub,
        onRetry = viewModel::retry,
        onPhotoClick = context::openPhoto,
    )
}

@Composable
fun PhotoMapScreen(
    uiState: PhotoMapUiState,
    showLocationPrompt: Boolean,
    onRequestMediaAccess: () -> Unit,
    onRequestDeviceLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    onTimelineScrub: (Float) -> Unit,
    onRetry: () -> Unit,
    onPhotoClick: (PhotoNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!uiState.permissions.canReadPhotos) {
        PermissionRequiredContent(
            permanentlyDenied = uiState.mediaPermissionPermanentlyDenied,
            onRequestAccess = onRequestMediaAccess,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    // Overlays are measured so the map can pad its camera framing and built-in controls around them.
    var topOverlayHeightPx by remember { mutableIntStateOf(0) }
    var timelineHeightPx by remember { mutableIntStateOf(0) }
    val safeDrawing = WindowInsets.safeDrawing
    val safeDrawingPadding = safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val timeline = uiState.timeline
    val mapPadding = with(density) {
        PaddingValues(
            start = safeDrawingPadding.calculateStartPadding(layoutDirection),
            top = topOverlayHeightPx.toDp(),
            end = safeDrawingPadding.calculateEndPadding(layoutDirection),
            bottom = if (timeline != null) timelineHeightPx.toDp() else safeDrawingPadding.calculateBottomPadding(),
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        PhotoClusterMap(
            allPhotos = uiState.allPhotos,
            visiblePhotos = uiState.visiblePhotos,
            showMyLocation = uiState.permissions.deviceLocationGranted,
            contentPadding = mapPadding,
            onPhotoClick = onPhotoClick,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topOverlayHeightPx = it.height }
                .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(16.dp),
        ) {
            if (uiState.permissions.mediaAccess == MediaAccess.Partial) {
                NoticeBanner(
                    message = stringResource(R.string.partial_access_banner),
                    actionLabel = stringResource(R.string.partial_access_action),
                    onAction = onRequestMediaAccess,
                )
            }
            if (showLocationPrompt) {
                FilledTonalButton(onClick = onRequestDeviceLocation, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.show_my_location))
                }
            }
        }

        LibraryStatusOverlay(
            uiState = uiState,
            onRequestMediaAccess = onRequestMediaAccess,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry,
            modifier = Modifier
                .align(Alignment.Center)
                .safeDrawingPadding()
                .padding(24.dp),
        )

        if (timeline != null) {
            TimelineScrubber(
                timeline = timeline,
                onScrub = onTimelineScrub,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { timelineHeightPx = it.height }
                    .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
            )
        }
    }
}

/** Scanning, empty and error states, drawn over the (empty) map. */
@Composable
private fun LibraryStatusOverlay(
    uiState: PhotoMapUiState,
    onRequestMediaAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.allPhotos.isNotEmpty()) return
    when (uiState.libraryStatus) {
        LibraryStatus.NoAccess -> Unit

        LibraryStatus.Scanning -> StatusCard(modifier) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.scanning_photos), style = MaterialTheme.typography.bodyLarge)
        }

        LibraryStatus.Ready -> StatusCard(modifier) {
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            if (uiState.permissions.mediaLocationGranted) {
                StatusBody(stringResource(R.string.empty_body))
            } else {
                // Without ACCESS_MEDIA_LOCATION every photo's GPS tags are redacted, which is the
                // likeliest reason for an empty map.
                StatusBody(stringResource(R.string.empty_media_location_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                    Button(onClick = onRequestMediaAccess) { Text(stringResource(R.string.allow)) }
                }
            }
        }

        LibraryStatus.Failed -> StatusCard(modifier) {
            Text(stringResource(R.string.scan_failed_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = modifier.widthIn(max = 360.dp), shape = RoundedCornerShape(24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun NoticeBanner(message: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PermissionRequiredContent(
    permanentlyDenied: Boolean,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(if (permanentlyDenied) R.string.permission_denied_body else R.string.permission_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            } else {
                Button(onClick = onRequestAccess) { Text(stringResource(R.string.permission_grant)) }
            }
        }
    }
}

private fun Context.openPhoto(photo: PhotoNode) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(photo.uri, "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_app_to_open_photo, Toast.LENGTH_SHORT).show()
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
