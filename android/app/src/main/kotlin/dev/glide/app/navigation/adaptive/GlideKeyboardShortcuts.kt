package dev.glide.app.navigation.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Global hardware keyboard shortcuts handler conforming to GLIDE_DESIGN_SYSTEM.md.
 * Supports:
 * - Ctrl+S / Cmd+S: Trigger Send flow
 * - Ctrl+R / Cmd+R: Trigger Receive flow
 * - Ctrl+H / Cmd+H: Navigate to History tab
 * - Ctrl+, / Cmd+,: Navigate to Settings tab
 * - Numbers 1, 2, 3 (with Ctrl/Cmd): Switch between Home, History, Settings
 * - Escape: Navigate back or cancel active modal/flow
 */
fun Modifier.glideGlobalKeyShortcuts(
    onNavigateHome: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateSettings: () -> Unit,
    onTriggerSend: (() -> Unit)? = null,
    onTriggerReceive: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    val isModifierActive = event.isCtrlPressed || event.isMetaPressed

    when {
        // Escape / Back key
        event.key == Key.Escape || event.key == Key.Back -> {
            if (onBack != null) {
                onBack()
                true
            } else {
                false
            }
        }

        // Ctrl+S / Cmd+S: Send
        isModifierActive && event.key == Key.S -> {
            if (onTriggerSend != null) {
                onTriggerSend()
                true
            } else {
                false
            }
        }

        // Ctrl+R / Cmd+R: Receive
        isModifierActive && event.key == Key.R -> {
            if (onTriggerReceive != null) {
                onTriggerReceive()
                true
            } else {
                false
            }
        }

        // Ctrl+H / Cmd+H: History
        isModifierActive && event.key == Key.H -> {
            onNavigateHistory()
            true
        }

        // Ctrl+, / Cmd+,: Settings
        isModifierActive && event.key == Key.Comma -> {
            onNavigateSettings()
            true
        }

        // Ctrl+1: Home tab
        isModifierActive && (event.key == Key.One || event.key == Key.NumPad1) -> {
            onNavigateHome()
            true
        }

        // Ctrl+2: History tab
        isModifierActive && (event.key == Key.Two || event.key == Key.NumPad2) -> {
            onNavigateHistory()
            true
        }

        // Ctrl+3: Settings tab
        isModifierActive && (event.key == Key.Three || event.key == Key.NumPad3) -> {
            onNavigateSettings()
            true
        }

        else -> false
    }
}
