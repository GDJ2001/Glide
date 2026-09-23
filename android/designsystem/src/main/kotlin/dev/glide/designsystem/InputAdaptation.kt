package dev.glide.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies a hand pointer cursor when hovered with a mouse or trackpad.
 * Conforms to GLIDE_DESIGN_SYSTEM.md input adaptation rules.
 */
fun Modifier.glideHoverHand(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)

/**
 * Applies an accessible visible focus ring when navigated via physical keyboard or D-pad.
 *
 * @param shape Shape matching the component border.
 * @param focusColor Accent color for the active focus indicator.
 * @param borderWidth Width of the focus ring stroke.
 */
fun Modifier.glideFocusRing(
    shape: Shape,
    focusColor: Color = Color(0xFF00E5FF), // Brand Cyan accent default
    borderWidth: Dp = 2.dp
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    this
        .focusable(interactionSource = interactionSource)
        .then(
            if (isFocused) {
                Modifier.border(width = borderWidth, color = focusColor, shape = shape)
            } else {
                Modifier
            }
        )
}

/**
 * Composite modifier for accessible, input-adapted interactive controls.
 * - Ensures >= 48dp interactive touch/click target.
 * - Displays Hand pointer cursor on mouse hover.
 * - Shows clear focus indication when focused with keyboard.
 */
fun Modifier.glideInteractiveTarget(
    shape: Shape? = null,
    onClick: (() -> Unit)? = null,
    role: Role? = Role.Button
): Modifier = composed {
    val hoverMod = this
        .minimumInteractiveComponentSize()
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .glideHoverHand()

    val clickMod = if (onClick != null) {
        hoverMod.clickable(role = role, onClick = onClick)
    } else {
        hoverMod
    }

    if (shape != null) {
        clickMod.glideFocusRing(shape = shape)
    } else {
        clickMod
    }
}
