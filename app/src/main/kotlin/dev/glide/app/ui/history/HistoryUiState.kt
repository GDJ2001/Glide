package dev.glide.app.ui.history

import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.core.domain.DeviceRole

enum class HistoryFilter {
    ALL,
    SENT,
    RECEIVED
}

data class HistorySessionItem(
    val sessionId: String,
    val role: DeviceRole,
    val peerLabel: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val failureCode: String? = null,
    val itemCount: Int = 0,
    val totalBytes: Long = 0L
)

data class HistoryItemDetail(
    val itemId: String,
    val ordinal: Int,
    val displayName: String,
    val mimeType: String,
    val offeredBytes: Long,
    val actualBytes: Long? = null,
    val outcome: String? = null,
    val failureCode: String? = null,
    val receivedFileKey: String? = null,
    val isFileAvailable: Boolean = false,
    val relativePath: String? = null
)

data class HistorySessionDetail(
    val session: HistorySessionItem,
    val items: List<HistoryItemDetail>
)

data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val sessions: List<HistorySessionItem> = emptyList(),
    val selectedSessionDetail: HistorySessionDetail? = null,
    val isLoading: Boolean = false,
    val isClearHistoryDialogOpen: Boolean = false,
    val openFileFeedback: String? = null,
    val exportStatus: ExportUiStatus = ExportUiStatus.Idle
)
