package dev.glide.app.ui.web

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.qr.QrEncoder
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.web.GlideWebGateway
import dev.glide.transport.lan.web.OfferedWebItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * ViewModel governing the local Web Share lifecycle (ADR-0019, Phases W1-W3).
 *
 * Coordinates starting the embedded HTTP server, generating the QR code onboarding bitmap,
 * driving the 120-second countdown timer, and cleanly shutting down the gateway.
 */
class WebShareViewModel(
    private val networkScope: LanNetworkScope? = null,
    private val qrBitmapGenerator: ((String) -> Bitmap?)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<WebShareUiState>(WebShareUiState.Idle)
    val uiState: StateFlow<WebShareUiState> = _uiState.asStateFlow()

    private var activeGateway: GlideWebGateway? = null
    private var countdownJob: Job? = null

    fun startSharing(
        items: List<OfferedWebItem>,
        deviceName: String = "Glide Android",
        ttlSeconds: Int = 120,
        quarantineDir: File? = null
    ) {
        stopSharing()

        if (items.isEmpty()) {
            _uiState.value = WebShareUiState.Error("No files selected to share.")
            return
        }

        _uiState.value = WebShareUiState.Starting

        val hostIp = resolveHostIp()
        val totalBytes = items.sumOf { it.fileSize }

        val effectiveQuarantineDir = quarantineDir ?: File(System.getProperty("java.io.tmpdir"), "glide_web_quarantine")

        try {
            val gateway = GlideWebGateway(
                host = hostIp,
                port = 0,
                offeredItems = items,
                deviceName = deviceName,
                platform = "android",
                quarantineDir = effectiveQuarantineDir,
                ttlSeconds = ttlSeconds
            )
            gateway.start()
            activeGateway = gateway

            val onboardingUrl = gateway.onboardingUrl
            val qrBitmap = try {
                qrBitmapGenerator?.invoke(onboardingUrl) ?: QrEncoder.encodeToBitmap(onboardingUrl, 512)
            } catch (_: Exception) {
                null
            }

            _uiState.value = WebShareUiState.Active(
                onboardingUrl = onboardingUrl,
                qrBitmap = qrBitmap,
                secondsRemaining = ttlSeconds,
                offeredItemCount = items.size,
                totalBytes = totalBytes
            )

            startCountdown(ttlSeconds)
        } catch (e: Exception) {
            _uiState.value = WebShareUiState.Error("Failed to start web server: ${e.message}")
        }
    }

    private fun startCountdown(initialSeconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = initialSeconds
            while (remaining > 0 && isActive) {
                delay(1000)
                remaining--
                val current = _uiState.value
                if (current is WebShareUiState.Active) {
                    _uiState.value = current.copy(secondsRemaining = remaining)
                }
            }
            if (isActive && _uiState.value is WebShareUiState.Active) {
                stopSharing()
            }
        }
    }

    fun stopSharing() {
        countdownJob?.cancel()
        countdownJob = null
        try {
            activeGateway?.close()
        } catch (_: Exception) {}
        activeGateway = null
        if (_uiState.value is WebShareUiState.Active || _uiState.value is WebShareUiState.Starting) {
            _uiState.value = WebShareUiState.Stopped
        }
    }

    fun dismiss() {
        stopSharing()
        _uiState.value = WebShareUiState.Idle
    }

    fun setUiStateForTesting(state: WebShareUiState) {
        _uiState.value = state
    }

    private fun resolveHostIp(): String {
        val state = networkScope?.networkState?.value
        if (state is LanNetworkState.Available) {
            val ipv4 = state.localAddresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (ipv4 != null) return ipv4.hostAddress ?: "127.0.0.1"
        }
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    override fun onCleared() {
        super.onCleared()
        stopSharing()
    }
}
