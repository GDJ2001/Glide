package dev.glide.app.ui.pairing

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.glide.app.qr.QrDecoder
import dev.glide.app.qr.QrFrameAnalyzer
import dev.glide.core.pairing.LanInvitation
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.brandCyanAccent
import dev.glide.designsystem.isolateBidi
import java.util.concurrent.Executors

/**
 * Offline QR code scanner and paste alternative screen for LAN pairing (LAN-018).
 *
 * Invariants:
 * - Camera is strictly optional; "Paste invitation" provides an identical validation path.
 * - Single-decode latching prevents duplicate frame triggers.
 * - Explicit confirmation required before connecting to scanned/pasted peer.
 * - Camera resources are deterministically unbound on stop/dispose.
 */
@Composable
fun QrScannerScreen(
    uiState: QrScannerUiState,
    onQrScanned: (String) -> Unit,
    onInvitationPasted: (String) -> Unit,
    onConfirmConnection: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onRetryScan: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showPasteDialog by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlashlight by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Toggle torch state on camera
    LaunchedEffect(torchEnabled, activeCamera) {
        try {
            activeCamera?.cameraControl?.enableTorch(torchEnabled)
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        if (hasCameraPermission) {
            // Camera Preview & Viewfinder
            CameraPreviewView(
                onQrDecoded = onQrScanned,
                onCameraBound = { camera ->
                    activeCamera = camera
                    hasFlashlight = camera.cameraInfo.hasFlashUnit()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder Overlay Cutout
            ViewfinderOverlay(modifier = Modifier.fillMaxSize())
        } else {
            // Camera Permission Educational Fallback Card
            CameraPermissionRationale(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onPasteClicked = { showPasteDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            )
        }

        // Top Navigation & Flashlight Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Close scanner and return"
                    }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            Text(
                text = "Scan QR code",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )

            if (hasCameraPermission && hasFlashlight) {
                IconButton(
                    onClick = { torchEnabled = !torchEnabled },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (torchEnabled) "Turn off flashlight" else "Turn on flashlight"
                        }
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = if (torchEnabled) MaterialTheme.colorScheme.brandCyanAccent else Color.White
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // Bottom Action Bar: Always provide Paste Invitation alternative
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(
                onClick = { showPasteDialog = true },
                shape = PrimaryButtonShape,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 52.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Paste invitation link without using camera"
                    }
            ) {
                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Paste invitation", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Confirmation Dialog (Section 8: explicit confirmation required before connect)
        if (uiState is QrScannerUiState.ConfirmationRequired) {
            ConnectionConfirmationDialog(
                invitation = uiState.invitation,
                onConfirm = onConfirmConnection,
                onCancel = onCancelConfirmation
            )
        }

        // Error Dialog
        if (uiState is QrScannerUiState.Error) {
            ScanErrorDialog(
                reason = uiState.reason,
                onRetry = onRetryScan,
                onPasteClicked = {
                    onRetryScan()
                    showPasteDialog = true
                }
            )
        }

        // Paste Invitation Dialog
        if (showPasteDialog) {
            PasteInvitationDialog(
                onInvitationEntered = { text ->
                    showPasteDialog = false
                    onInvitationPasted(text)
                },
                onDismiss = { showPasteDialog = false }
            )
        }
    }
}

@Composable
private fun CameraPreviewView(
    onQrDecoded: (String) -> Unit,
    onCameraBound: (Camera) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val frameAnalyzer = remember {
        QrFrameAnalyzer(
            onQrDecoded = { result ->
                onQrDecoded(result)
            }
        )
    }

    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                try {
                    activeCameraProvider?.unbindAll()
                } catch (_: Throwable) {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                activeCameraProvider?.unbindAll()
            } catch (_: Throwable) {}
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                activeCameraProvider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, frameAnalyzer)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    onCameraBound(camera)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.brandCyanAccent,
                        shape = RoundedCornerShape(24.dp)
                    )
            )

            Text(
                text = "Align QR code within frame",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    onPasteClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Camera access for QR scan",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Glide uses the camera solely to scan offline pairing QR codes on your local network. No photos or video are ever stored or uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onRequestPermission,
                    shape = PrimaryButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Grant camera access")
                }

                OutlinedButton(
                    onClick = onPasteClicked,
                    shape = PrimaryButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Paste invitation instead")
                }
            }
        }
    }
}

@Composable
private fun ConnectionConfirmationDialog(
    invitation: LanInvitation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val endpoint = invitation.endpoints.firstOrNull()
    val endpointText = if (endpoint != null) "${endpoint.address}:${endpoint.port}" else "Local network"

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "Connect to ${invitation.name.isolateBidi()}?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A local transfer invitation was detected. Both devices must be on the same local network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Endpoint: $endpointText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = PrimaryButtonShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ScanErrorDialog(
    reason: QrScanErrorReason,
    onRetry: () -> Unit,
    onPasteClicked: () -> Unit
) {
    val error = dev.glide.app.ui.failure.ActionableErrorMapper.fromQrScanErrorReason(reason)

    AlertDialog(
        onDismissRequest = onRetry,
        icon = {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = { Text(error.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = error.userWording,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (error.troubleshootingHint != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error.troubleshootingHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRetry,
                shape = PrimaryButtonShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(error.primaryActionLabel)
            }
        },
        dismissButton = {
            if (error.secondaryActionLabel != null) {
                TextButton(
                    onClick = onPasteClicked,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(error.secondaryActionLabel)
                }
            }
        }
    )
}

@Composable
private fun PasteInvitationDialog(
    onInvitationEntered: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var textValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste invitation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste the glide://pair/v1 invitation link copied from the receiving device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    placeholder = { Text("glide://pair/v1#...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4
                )

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val item = clipboard?.primaryClip?.getItemAt(0)
                        val pasteText = item?.text?.toString() ?: ""
                        if (pasteText.isNotBlank()) {
                            textValue = pasteText
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste from clipboard")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvitationEntered(textValue) },
                enabled = textValue.isNotBlank(),
                shape = PrimaryButtonShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Verify & connect")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}
