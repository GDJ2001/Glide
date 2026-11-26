package dev.glide.app.ui.pairing

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.app.connectivity.ConnectionKind
import dev.glide.app.ui.discovery.rememberConnectionSetup
import dev.glide.core.pairing.HotspotInvitation
import dev.glide.transport.lan.network.AndroidHotspotConnections
import dev.glide.transport.lan.network.LanAddressValidationResult
import dev.glide.transport.lan.network.LanAddressValidator
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

@Composable
internal fun HotspotJoinDialog(
    invitation: HotspotInvitation,
    onReady: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyNotice by remember(invitation) { mutableStateOf<String?>(null) }
    var joining by remember(invitation) { mutableStateOf(false) }
    var manual by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var attempted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var notice by remember(invitation) { mutableStateOf<String?>(null) }
    val setup = rememberConnectionSetup()
    val completeJoin: () -> Unit = {
        if (invitation.pairing.expiresAt.toLong() > Instant.now().epochSecond) {
            onReady()
        } else {
            AndroidHotspotConnections.closeUnclaimed()
            notice = "This hotspot code has expired. Scan a new code."
        }
    }
    val tryJoin: () -> Unit = {
        joining = true
        scope.launch {
            try {
                if (AndroidHotspotConnections.join(
                        context, invitation.ssid, invitation.passphrase, invitation.pairing.expiresAt.toLong()
                    )) {
                    completeJoin()
                } else {
                    manual = true
                }
            } finally {
                joining = false
            }
        }
    }
    val checkManualConnection: () -> Unit = {
        if (!joining) {
            joining = true
            scope.launch {
                try {
                    val ready = withContext(Dispatchers.IO) {
                        LanNetworkScope(context.applicationContext).use { network ->
                            withTimeoutOrNull(2_500) {
                                network.networkState.filterIsInstance<LanNetworkState.Available>().first { state ->
                                    invitation.pairing.endpoints.any { endpoint ->
                                        LanAddressValidator.validateAddress(endpoint.address, state.subnets) ==
                                            LanAddressValidationResult.Valid
                                    }
                                }
                            } != null
                        }
                    }
                    if (ready) completeJoin()
                    else notice = "Connect to the hotspot in Wi-Fi settings."
                } finally {
                    joining = false
                }
            }
        }
    }
    val wifiSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkManualConnection()
    }
    androidx.compose.runtime.LaunchedEffect(invitation) {
        if (!attempted) {
            attempted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setup.start(ConnectionKind.Hotspot, onBlocked = { manual = true }, onReady = tryJoin)
            } else {
                manual = true
            }
        } else if (setup.pending == null && !joining) {
            // An Activity recreation can interrupt the OS network chooser.
            manual = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!joining) onDismiss() },
        title = { Text("Join hotspot") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HotspotCredentials(invitation.ssid, invitation.passphrase) { copyNotice = it }
                copyNotice?.let { message ->
                    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (manual) {
                    Text("Join this network in Wi-Fi settings, then return to Glide.")
                    GlideIconAction(Icons.Default.Settings, "Open Wi-Fi settings", {
                        runCatching { wifiSettings.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    })
                }
            }
        },
        confirmButton = {
            if (manual) {
                Button(enabled = !joining, onClick = checkManualConnection) { Text("Continue") }
            } else {
                Text(if (joining) "Connecting…" else "Preparing connection…")
            }
        },
        dismissButton = {
            TextButton(enabled = !joining, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
