package com.droidspaces.app.ui.screen

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.droidspaces.app.wayland.WaylandDisplayLayout
import com.droidspaces.app.wayland.WaylandDisplayView
import com.droidspaces.app.wayland.WaylandManager
import com.droidspaces.app.wayland.WaylandSurface

/**
 * Wayland compositor display.
 *
 * Fullscreen mode:
 *   - TopAppBar slides away (AnimatedVisibility)
 *   - Status bar + nav bar hidden via WindowInsetsControllerCompat
 *     (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE — swipe to peek, auto-hides)
 *   - Restored immediately on exit-fullscreen or navigate-back
 *
 * IME / keyboard:
 *   - Bottom toolbar has a keyboard toggle button (shows/hides soft keyboard)
 *   - Display area is fixed — the compositor surface never resizes for IME
 *   - Bottom toolbar uses imePadding() so it slides above the soft keyboard
 *   - SUP key replaced with keyboard toggle icon
 *
 * Bottom toolbar: fullscreen ▏ ESC TAB CTRL ALT ▏ ⌨ ▏ ↑ ↓ ← →
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaylandScreen(onNavigateBack: () -> Unit) {
    val isRunning        = WaylandManager.isRunning
    var isFullscreen     by remember { mutableStateOf(false) }

    var isKeyboardVisible by remember { mutableStateOf(false) }
    var waylandLayout: WaylandDisplayLayout? by remember { mutableStateOf(null) }

    val view             = LocalView.current

    // Derive the WindowInsetsController once from the hosting Activity's window.
    val insetsController = remember(view) {
        val activity = view.context as? Activity ?: return@remember null
        WindowCompat.getInsetsController(activity.window, view)
    }

    // Sync system-bar visibility with fullscreen state.
    LaunchedEffect(isFullscreen) {
        insetsController?.let { ctrl ->
            if (isFullscreen) {
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Always restore bars when the screen leaves composition.
    DisposableEffect(insetsController) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (isFullscreen) isFullscreen = false else onNavigateBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── TopAppBar ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = !isFullscreen,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Wayland Display",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        val chipColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                                        else          MaterialTheme.colorScheme.errorContainer
                        val textColor = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                        else          MaterialTheme.colorScheme.onErrorContainer
                        Surface(
                            shape    = MaterialTheme.shapes.small,
                            color    = chipColor,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Text(
                                if (isRunning) "Live" else "Stopped",
                                color      = textColor,
                                style      = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // ── Display area — fixed size, never resizes for IME ─────────────────
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (isRunning) {
                WaylandDisplayView(
                    modifier    = Modifier.fillMaxSize(),
                    onViewReady = { waylandLayout = it },
                )
            } else {
                CompositorOffPlaceholder(onNavigateBack)
            }
        }

        // ── Bottom toolbar ───────────────────────────────────────────────────
        if (isRunning) {
            WaylandKeyboardBar(
                isFullscreen       = isFullscreen,
                isKeyboardVisible  = isKeyboardVisible,
                onFullscreenToggle = { isFullscreen = !isFullscreen },
                onKeyboardToggle   = {
                    if (isKeyboardVisible) {
                        waylandLayout?.hideKeyboard()
                        isKeyboardVisible = false
                    } else {
                        waylandLayout?.showKeyboard()
                        isKeyboardVisible = true
                    }
                },
            )
        }
    }
}

// ── Bottom keyboard bar ──────────────────────────────────────────────────────

@Composable
private fun WaylandKeyboardBar(
    isFullscreen: Boolean,
    isKeyboardVisible: Boolean,
    onFullscreenToggle: () -> Unit,
    onKeyboardToggle: () -> Unit,
) {
    Surface(
        color          = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier       = Modifier.imePadding(),  // toolbar slides up over IME; compositor stays fixed
    ) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()     // absorb gesture nav bar height
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Fullscreen toggle
                WlIconKey(
                    icon    = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    desc    = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                    onClick = onFullscreenToggle,
                )

                VerticalDivider(modifier = Modifier.height(26.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // Special keys
                WlTextKey("ESC",  KeyEvent.KEYCODE_ESCAPE)
                WlTextKey("TAB",  KeyEvent.KEYCODE_TAB)
                WlTextKey("CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
                WlTextKey("ALT",  KeyEvent.KEYCODE_ALT_LEFT)

                // Keyboard toggle (replaces SUP)
                WlIconKey(
                    icon    = if (isKeyboardVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    desc    = "Toggle keyboard",
                    onClick = onKeyboardToggle,
                )

                VerticalDivider(modifier = Modifier.height(26.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // Arrow keys
                WlIconKey(Icons.Default.KeyboardArrowUp,                "↑", keyCode = KeyEvent.KEYCODE_DPAD_UP)
                WlIconKey(Icons.Default.KeyboardArrowDown,              "↓", keyCode = KeyEvent.KEYCODE_DPAD_DOWN)
                WlIconKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft,  "←", keyCode = KeyEvent.KEYCODE_DPAD_LEFT)
                WlIconKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "→", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
    }
}

// ── Key button helpers ───────────────────────────────────────────────────────

@Composable
private fun RowScope.WlTextKey(label: String, keyCode: Int) {
    TextButton(
        onClick        = { sendKey(keyCode) },
        modifier       = Modifier.weight(1f).fillMaxHeight(),
        shape          = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            label,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines   = 1,
        )
    }
}

@Composable
private fun RowScope.WlIconKey(
    icon: ImageVector,
    desc: String,
    keyCode: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    IconButton(
        onClick  = onClick ?: { if (keyCode != null) sendKey(keyCode) },
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Icon(icon, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sendKey(keyCode: Int) {
    val t = (SystemClock.uptimeMillis() and 0x7FFF_FFFFL).toInt()
    WaylandSurface.nativeEnsureFocus()   // ensure wl_keyboard.enter before key delivery
    WaylandSurface.nativeOnKeyEvent(keyCode, true,  t)
    WaylandSurface.nativeOnKeyEvent(keyCode, false, t + 1)
}

// ── Compositor-off placeholder ───────────────────────────────────────────────

@Composable
private fun CompositorOffPlaceholder(onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier            = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Default.DesktopWindows, null,
            modifier = Modifier.size(48.dp),
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Text("Wayland compositor is not running",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Enable it in Settings → Wayland Compositor, then come back here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onNavigateBack, shape = MaterialTheme.shapes.medium) {
            Text("Go Back", fontWeight = FontWeight.SemiBold)
        }
    }
}
