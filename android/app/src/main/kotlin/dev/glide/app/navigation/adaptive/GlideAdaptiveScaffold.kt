package dev.glide.app.navigation.adaptive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.glide.app.R
import dev.glide.app.navigation.GlideRoute
import dev.glide.designsystem.AdaptiveLayoutTokens
import dev.glide.designsystem.WindowSizeClass

/**
 * Adaptive layout scaffold that provides:
 * - Bottom [NavigationBar] for compact portrait phones (< 600dp width).
 * - Start-aligned [NavigationRail] for medium / expanded screens (>= 600dp width).
 * - Content width constraint (max 1200dp) centered horizontally on wide displays.
 *
 * Implements M7 GLIDE-044 adaptive navigation requirements.
 */
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.LocalDevicePosture

@Composable
fun GlideAdaptiveScaffold(
    windowSizeClass: WindowSizeClass,
    currentRoute: GlideRoute,
    onNavigateTopLevel: (GlideRoute) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = androidx.compose.runtime.remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    val useDarkBarIcons = !dev.glide.designsystem.LocalGlideDarkTheme.current && currentRoute !is GlideRoute.QrScanner
    androidx.compose.runtime.SideEffect {
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = useDarkBarIcons
                isAppearanceLightNavigationBars = useDarkBarIcons
            }
        }
    }
    val postureInfo = LocalDevicePosture.current
    val isHome = currentRoute is GlideRoute.Home ||
            currentRoute is GlideRoute.Selection ||
            currentRoute is GlideRoute.Discovery ||
            currentRoute is GlideRoute.ReceiveReadiness ||
            currentRoute is GlideRoute.Transfer
    val isHistory = currentRoute is GlideRoute.History
    val isSettings = currentRoute is GlideRoute.Settings

    val isTopLevelRoute = currentRoute is GlideRoute.Home ||
            currentRoute is GlideRoute.History ||
            currentRoute is GlideRoute.Settings

    val scaffoldModifier = modifier
        .fillMaxSize()
        .glideGlobalKeyShortcuts(
            onNavigateHome = { onNavigateTopLevel(GlideRoute.Home) },
            onNavigateHistory = { onNavigateTopLevel(GlideRoute.History()) },
            onNavigateSettings = { onNavigateTopLevel(GlideRoute.Settings) }
        )

    val movableContent = androidx.compose.runtime.remember(content) {
        androidx.compose.runtime.movableContentOf { padding: PaddingValues ->
            content(padding)
        }
    }

    if (currentRoute is GlideRoute.QrScanner) {
        // Let the scanner own its safe insets and draw behind transparent system bars.
        Box(modifier = scaffoldModifier.background(Color.Black)) {
            movableContent(PaddingValues(0.dp))
        }
    } else if (windowSizeClass.isCompactWidth) {
        // Compact Phone: Bottom Navigation Bar on top-level routes, hidden during focused workflows
        Scaffold(
            modifier = scaffoldModifier,
            bottomBar = {
                if (isTopLevelRoute) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.semantics {
                            contentDescription = "Main navigation bar"
                        }
                    ) {
                        NavigationBarItem(
                            selected = isHome,
                            onClick = { onNavigateTopLevel(GlideRoute.Home) },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("Home") },
                            modifier = Modifier
                                .glideHoverHand()
                                .semantics {
                                    contentDescription = "Navigate to Home tab"
                                }
                        )
                        NavigationBarItem(
                            selected = isHistory,
                            onClick = { onNavigateTopLevel(GlideRoute.History()) },
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text("History") },
                            modifier = Modifier
                                .glideHoverHand()
                                .semantics {
                                    contentDescription = "Navigate to History tab"
                                }
                        )
                        NavigationBarItem(
                            selected = isSettings,
                            onClick = { onNavigateTopLevel(GlideRoute.Settings) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                            modifier = Modifier
                                .glideHoverHand()
                                .semantics {
                                    contentDescription = "Navigate to Settings tab"
                                }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                movableContent(innerPadding)
            }
        }
    } else {
        // Medium & Expanded: Navigation Rail on the start side
        val isDark = dev.glide.designsystem.LocalGlideDarkTheme.current
        val logoRes = if (isDark) R.drawable.ic_glide_logo_inverse else R.drawable.ic_glide_logo_color

        Row(
            modifier = scaffoldModifier
                .safeDrawingPadding()
        ) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                header = {
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        painter = painterResource(id = logoRes),
                        contentDescription = "Glide",
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = "Main navigation rail"
                    }
            ) {
                NavigationRailItem(
                    selected = isHome,
                    onClick = { onNavigateTopLevel(GlideRoute.Home) },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") },
                    modifier = Modifier
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Navigate to Home section"
                        }
                )
                NavigationRailItem(
                    selected = isHistory,
                    onClick = { onNavigateTopLevel(GlideRoute.History()) },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") },
                    modifier = Modifier
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Navigate to History section"
                        }
                )
                NavigationRailItem(
                    selected = isSettings,
                    onClick = { onNavigateTopLevel(GlideRoute.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    modifier = Modifier
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Navigate to Settings section"
                        }
                )
            }

            // Constrained main content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = AdaptiveLayoutTokens.MaxContentWidth)
                ) {
                    movableContent(PaddingValues(0.dp))
                }
            }
        }
    }
}
