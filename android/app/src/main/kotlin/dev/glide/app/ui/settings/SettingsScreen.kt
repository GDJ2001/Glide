package dev.glide.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.ThemePreference
import dev.glide.core.domain.TrustedPeerRecord
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onPairDevice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (uri.authority != "com.android.externalstorage.documents") {
                viewModel.showSaveLocationError("Choose a folder on this device or an SD card.")
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setReceivedFilesFolder(uri.toString())
            } catch (_: SecurityException) {
                viewModel.showSaveLocationError("Glide needs folder access to save there. Choose another folder.")
            }
        }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val message = uiState.feedbackMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearFeedbackMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back from settings"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val windowSizeClass = dev.glide.designsystem.LocalWindowSizeClass.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .widthIn(max = dev.glide.designsystem.AdaptiveLayoutTokens.MaxContentWidth)
                .padding(horizontal = windowSizeClass.pageHorizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            val haptics = dev.glide.designsystem.GlideTheme.haptics

            // 1. Device Section
            DeviceNameSection(
                deviceName = uiState.settings.deviceName,
                onEditClick = {
                    haptics.performSelection()
                    viewModel.startEditDeviceName()
                }
            )

            // 2. Appearance Section
            AppearanceSection(
                selectedTheme = uiState.settings.theme,
                onThemeSelected = {
                    haptics.performSelection()
                    viewModel.setTheme(it)
                },
                dynamicColor = uiState.settings.dynamicColor,
                onDynamicColorChanged = {
                    haptics.performSelection()
                    viewModel.setDynamicColor(it)
                },
                reducedMotion = uiState.settings.reducedMotion,
                onReducedMotionChanged = {
                    haptics.performSelection()
                    viewModel.setReducedMotion(it)
                }
            )

            // 3. Privacy & Transfer Behavior Section
            PrivacyAndBehaviorSection(
                notificationDetails = uiState.settings.notificationDetails,
                onNotificationDetailsChanged = {
                    haptics.performSelection()
                    viewModel.setNotificationDetails(it)
                },
                keepScreenAwake = uiState.settings.keepScreenAwake,
                onKeepScreenAwakeChanged = {
                    haptics.performSelection()
                    viewModel.setKeepScreenAwake(it)
                }
            )

            // 4. Trusted Devices Section
            TrustedDevicesSection(
                trustedPeerCount = uiState.trustedPeers.size,
                onPairDevice = onPairDevice,
                onManageClick = {
                    haptics.performSelection()
                    viewModel.showTrustedDevicesDialog()
                }
            )

            // 5. Private Storage & File Management Section
            PrivateStorageSection(
                totalFiles = uiState.storageSummary.totalFiles,
                totalBytes = uiState.storageSummary.totalBytes,
                activeFiles = uiState.storageSummary.activeFiles,
                isDeleting = uiState.isDeleting,
                onDeleteAllClick = {
                    haptics.performSelection()
                    viewModel.requestDeleteAllFiles()
                }
            )

            ReceiveSaveLocationSection(
                treeUri = uiState.settings.receivedFilesTreeUri,
                onChooseFolder = { folderPicker.launch(null) },
                onUseDefault = { viewModel.setReceivedFilesFolder(null) }
            )

            // 5. Nearby & Local Privacy Section
            NearbyPrivacySection(
                onViewDiagnosticsClick = {
                    haptics.performSelection()
                    viewModel.showNearbyDiagnostics()
                }
            )

            // 6. About Glide Section
            AboutSection(
                versionName = uiState.appVersionName,
                buildNumber = uiState.appBuildNumber,
                onLicensesClick = {
                    haptics.performSelection()
                    viewModel.showLicensesDialog()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Edit Device Name Dialog
    if (uiState.isEditingDeviceName) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelEditDeviceName() },
            title = { Text("Device name") },
            text = {
                Column {
                    Text(
                        text = "This name is visible to nearby devices when discovering and connecting.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = uiState.deviceNameInput,
                        onValueChange = { viewModel.onDeviceNameInputChange(it) },
                        label = { Text("Name") },
                        isError = uiState.deviceNameError != null,
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = uiState.deviceNameError ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "${uiState.deviceNameInput.length}/${GlideSettings.MAX_DEVICE_NAME_LENGTH}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveDeviceName() },
                    enabled = uiState.deviceNameError == null && uiState.deviceNameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelEditDeviceName() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete All Files Confirmation Dialog
    if (uiState.isDeleteAllConfirmOpen) {
        val count = uiState.storageSummary.totalFiles
        val sizeFormatted = formatBytes(uiState.storageSummary.totalBytes)
        val activeCount = uiState.storageSummary.activeFiles

        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteAllFilesDialog() },
            title = { Text("Delete received files?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Permanently delete $count received files ($sizeFormatted) from Glide's private storage? This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Files previously exported to Downloads or custom locations will not be affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeCount > 0) {
                        Text(
                            text = "Note: $activeCount file(s) in active transfers will be protected and preserved.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteAllFiles() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete files")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissDeleteAllFilesDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Nearby Diagnostics Dialog
    if (uiState.isNearbyDiagnosticsOpen) {
        NearbyDiagnosticsDialog(
            diagnostics = uiState.nearbyDiagnostics,
            onDismiss = { viewModel.dismissNearbyDiagnostics() }
        )
    }

    // Open Source Licenses Dialog
    if (uiState.isLicensesDialogOpen) {
        LicensesDialog(
            onDismiss = { viewModel.dismissLicensesDialog() }
        )
    }

    // Trusted Devices Dialog
    if (uiState.isTrustedDevicesDialogOpen) {
        TrustedDevicesDialog(
            trustedPeers = uiState.trustedPeers,
            onRevokePeer = { fingerprint ->
                viewModel.revokeTrustedPeer(fingerprint)
            },
            onDismiss = { viewModel.dismissTrustedDevicesDialog() }
        )
    }
}

@Composable
private fun ReceiveSaveLocationSection(
    treeUri: String?,
    onChooseFolder: () -> Unit,
    onUseDefault: () -> Unit
) {
    val location = if (treeUri == null) "Download/Glide" else runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).substringAfter(':')
    }.getOrDefault("Selected folder")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Received file save location", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(location, style = MaterialTheme.typography.bodyLarge)
        Text("Verified files save here automatically. Glide also keeps a private copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (android.os.Build.VERSION.SDK_INT < 29 && treeUri == null) {
            Text("On this Android version, choose a local folder to enable automatic saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseFolder) { Text("Choose folder") }
            if (treeUri != null) {
                TextButton(onClick = onUseDefault) { Text("Use Download/Glide") }
            }
        }
    }
}

@Composable
private fun DeviceNameSection(
    deviceName: String,
    onEditClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Device",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Visible to nearby devices",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onEditClick,
                    modifier = Modifier.semantics {
                        contentDescription = "Edit device name"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    selectedTheme: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChanged: (Boolean) -> Unit,
    reducedMotion: ReducedMotionPreference,
    onReducedMotionChanged: (ReducedMotionPreference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Theme mode",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    ThemeOption(
                        label = "System default",
                        selected = selectedTheme == ThemePreference.SYSTEM,
                        onClick = { onThemeSelected(ThemePreference.SYSTEM) }
                    )
                    ThemeOption(
                        label = "Light",
                        selected = selectedTheme == ThemePreference.LIGHT,
                        onClick = { onThemeSelected(ThemePreference.LIGHT) }
                    )
                    ThemeOption(
                        label = "Dark",
                        selected = selectedTheme == ThemePreference.DARK,
                        onClick = { onThemeSelected(ThemePreference.DARK) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic color",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Use system wallpaper accent colors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = onDynamicColorChanged,
                        modifier = Modifier.semantics {
                            contentDescription = "Toggle dynamic color"
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Reduced motion",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Reduce or disable animations and transitions throughout Glide",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    ThemeOption(
                        label = "System default",
                        selected = reducedMotion == ReducedMotionPreference.SYSTEM,
                        onClick = { onReducedMotionChanged(ReducedMotionPreference.SYSTEM) }
                    )
                    ThemeOption(
                        label = "On",
                        selected = reducedMotion == ReducedMotionPreference.ON,
                        onClick = { onReducedMotionChanged(ReducedMotionPreference.ON) }
                    )
                    ThemeOption(
                        label = "Off",
                        selected = reducedMotion == ReducedMotionPreference.OFF,
                        onClick = { onReducedMotionChanged(ReducedMotionPreference.OFF) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PrivacyAndBehaviorSection(
    notificationDetails: Boolean,
    onNotificationDetailsChanged: (Boolean) -> Unit,
    keepScreenAwake: Boolean,
    onKeepScreenAwakeChanged: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Privacy & behavior",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notification details",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Show file and peer names in system notifications",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationDetails,
                        onCheckedChange = onNotificationDetailsChanged,
                        modifier = Modifier.semantics {
                            contentDescription = "Toggle notification details"
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep screen awake",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Prevent display sleep during active file transfers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = keepScreenAwake,
                        onCheckedChange = onKeepScreenAwakeChanged,
                        modifier = Modifier.semantics {
                            contentDescription = "Toggle keep screen awake"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateStorageSection(
    totalFiles: Int,
    totalBytes: Long,
    activeFiles: Int,
    isDeleting: Boolean,
    onDeleteAllClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Private storage & files",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        // Storage usage and action
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Storage used",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (totalFiles == 0) "No received files" else "$totalFiles files · ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (activeFiles > 0) {
                        Text(
                            text = "($activeFiles files in active transfer)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = onDeleteAllClick,
                        enabled = totalFiles > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Delete all received files from private storage"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete all")
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyPrivacySection(
    onViewDiagnosticsClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onViewDiagnosticsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "View Nearby diagnostics"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nearby diagnostics")
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    versionName: String,
    buildNumber: String,
    onLicensesClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "About Glide",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Glide",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Version $versionName (Build $buildNumber)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = onLicensesClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Open source licenses"
                        }
                ) {
                    Text("Open source licenses")
                }
            }
        }
    }
}

@Composable
private fun NearbyDiagnosticsDialog(
    diagnostics: NearbyDiagnosticsState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nearby Diagnostics")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Radio & Service Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                DiagnosticRow(
                    label = "Bluetooth LE",
                    value = if (diagnostics.bluetoothEnabled) "Active / Ready" else "Disabled",
                    isGood = diagnostics.bluetoothEnabled
                )
                DiagnosticRow(
                    label = "Local Wi-Fi",
                    value = if (diagnostics.wifiEnabled) "Active / Ready" else "Disabled",
                    isGood = diagnostics.wifiEnabled
                )
                DiagnosticRow(
                    label = "Nearby Permissions",
                    value = if (diagnostics.permissionsGranted) "Granted" else "Missing permissions",
                    isGood = diagnostics.permissionsGranted
                )
                DiagnosticRow(
                    label = "Radio Link Mode",
                    value = diagnostics.radioBand,
                    isGood = true
                )
                DiagnosticRow(
                    label = "GMS Nearby API",
                    value = diagnostics.gmsVersion,
                    isGood = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    isGood: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LicensesDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Source Licenses") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LicenseItem(
                    name = "Glide",
                    license = "Apache License 2.0",
                    description = "Copyright (c) 2026 Glide Contributors\nLicensed under the Apache License, Version 2.0."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "Android Jetpack & Compose",
                    license = "Apache License 2.0",
                    description = "Copyright (c) The Android Open Source Project\nLicensed under the Apache License, Version 2.0."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "Google Play services Nearby Connections",
                    license = "Google Play Services Terms",
                    description = "Copyright (c) Google LLC\nProvided under the Google APIs Terms of Service."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "Kotlin & Kotlinx Coroutines",
                    license = "Apache License 2.0",
                    description = "Copyright (c) JetBrains s.r.o. and Kotlin Programming Language contributors."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "Dagger Hilt",
                    license = "Apache License 2.0",
                    description = "Copyright (c) 2020 The Dagger Authors\nLicensed under the Apache License, Version 2.0."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "Protocol Buffers",
                    license = "BSD 3-Clause License",
                    description = "Copyright (c) 2008 Google Inc. All rights reserved."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "AndroidX CameraX",
                    license = "Apache License 2.0",
                    description = "Copyright (c) The Android Open Source Project\nLicensed under the Apache License, Version 2.0."
                )
                HorizontalDivider()
                LicenseItem(
                    name = "ZXing Core",
                    license = "Apache License 2.0",
                    description = "Copyright (c) 2008 ZXing authors\nLicensed under the Apache License, Version 2.0."
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun LicenseItem(
    name: String,
    license: String,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = license,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrustedDevicesSection(
    trustedPeerCount: Int,
    onPairDevice: () -> Unit,
    onManageClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Trusted Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remembered Devices",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (trustedPeerCount == 1) "1 device identity enrolled"
                            else "$trustedPeerCount device identities enrolled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = onManageClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Manage trusted devices"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage trusted devices")
                }
                OutlinedButton(
                    onClick = onPairDevice,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Pair with another device")
                }
            }
        }
    }
}

@Composable
private fun TrustedDevicesDialog(
    trustedPeers: List<TrustedPeerRecord>,
    onRevokePeer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Trusted Devices")
            }
        },
        text = {
            if (trustedPeers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No trusted devices",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Devices explicitly enrolled after cryptographic identity verification will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    trustedPeers.forEach { peer ->
                        val truncatedFingerprint = if (peer.peerFingerprintHex.length > 16) {
                            "${peer.peerFingerprintHex.take(8)}...${peer.peerFingerprintHex.takeLast(8)}"
                        } else {
                            peer.peerFingerprintHex
                        }
                        val pairedDate = remember(peer.pairedAtMs) {
                            dateFormat.format(java.util.Date(peer.pairedAtMs))
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = peer.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "SHA-256: $truncatedFingerprint",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Paired on $pairedDate",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRevokePeer(peer.peerFingerprintHex) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Revoke trust for ${peer.displayName}"
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
