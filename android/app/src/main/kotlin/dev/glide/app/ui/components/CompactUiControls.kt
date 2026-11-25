package dev.glide.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glide.designsystem.glideHoverHand

internal enum class ActionTone { Neutral, Primary, Destructive }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlideIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.Neutral,
    tooltip: String = label
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (tone) {
        ActionTone.Neutral -> IconButtonDefaults.filledTonalIconButtonColors()
        ActionTone.Primary -> IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        )
        ActionTone.Destructive -> IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer
        )
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                colors = colors,
                modifier = modifier.glideHoverHand()
            ) {
                Icon(icon, contentDescription = label)
            }
        }
    }
}

@Composable
internal fun GlideCompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Navigate back",
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        onBack?.let { GlideIconAction(Icons.AutoMirrored.Filled.ArrowBack, backLabel, it) }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}
