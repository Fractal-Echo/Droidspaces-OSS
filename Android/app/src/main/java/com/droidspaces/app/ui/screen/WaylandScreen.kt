package com.droidspaces.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidspaces.app.wayland.WaylandDisplayView
import com.droidspaces.app.wayland.WaylandManager

/**
 * Fullscreen Wayland compositor display screen.
 *
 * Design language: identical to ContainerTerminalScreen —
 *   surfaceContainerLow TopAppBar, no bottom navigation, back pops the screen
 *   but the compositor keeps running.
 *
 * The screen is always global — there is exactly one Wayland compositor for
 * the entire app, so this screen just connects a SurfaceView to it.
 * Navigating away does NOT stop the compositor; nativeSurfaceDestroyed() will
 * restart headless dispatch automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaylandScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val isRunning = WaylandManager.isRunning

    // Physical back → go back, compositor stays alive.
    BackHandler { onNavigateBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Wayland Display",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Compositor status chip
                    val (chipColor, chipText) = if (isRunning)
                        MaterialTheme.colorScheme.primaryContainer to "Live"
                    else
                        MaterialTheme.colorScheme.errorContainer to "Stopped"
                    val (textColor) = if (isRunning)
                        listOf(MaterialTheme.colorScheme.onPrimaryContainer)
                    else
                        listOf(MaterialTheme.colorScheme.onErrorContainer)

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = chipColor,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            text = chipText,
                            color = textColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (isRunning) {
                // EGL surface — fills the content area
                WaylandDisplayView(
                    resolutionPercent = 100,
                    scalePercent = 100,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Compositor not running — friendly nudge
                CompositorOffPlaceholder(onNavigateBack = onNavigateBack)
            }
        }
    }
}

@Composable
private fun CompositorOffPlaceholder(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DesktopWindows,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Text(
            text = "Wayland compositor is not running",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Enable it in Settings → Wayland Compositor, then come back here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onNavigateBack,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Go Back", fontWeight = FontWeight.SemiBold)
        }
    }
}
