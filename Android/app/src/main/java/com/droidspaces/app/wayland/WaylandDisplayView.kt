package com.droidspaces.app.wayland

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable wrapper around SurfaceView that drives the Wayland compositor renderer.
 *
 * Responsibilities:
 *  - Surface lifecycle → JNI (nativeSurfaceCreated / nativeSurfaceDestroyed)
 *  - Touch events → pointer events (tablet/direct mode: finger = cursor)
 *  - Physical mouse → pointer / scroll events
 *  - Hardware keyboard → Linux key codes via keycode_map
 *  - Output size reported back to compositor on every surface change
 *
 * This is intentionally simple: single-pointer tablet mode only. Touchpad
 * cursor simulation (WaylandTouchpadController) can be added later; for now
 * finger position maps 1:1 to Wayland logical coordinates.
 */
@Composable
fun WaylandDisplayView(
    resolutionPercent: Int = 100,
    scalePercent: Int = 100,
    modifier: Modifier = Modifier,
) {
    val rp = resolutionPercent.coerceIn(10, 100)
    val sp = scalePercent.coerceIn(100, 1000)

    AndroidView(
        factory = { ctx ->
            WaylandDisplayLayout(ctx, rp, sp)
        },
        update = { view ->
            (view as? WaylandDisplayLayout)?.updateParams(rp, sp)
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Internal View
// ---------------------------------------------------------------------------

private class WaylandDisplayLayout(
    context: Context,
    private var resolutionPercent: Int,
    private var scalePercent: Int,
) : FrameLayout(context) {

    private var surfaceW = 0
    private var surfaceH = 0

    init {
        val surfaceView = object : SurfaceView(context) {
            // Forward hardware key events from SurfaceView focus
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                val linux = AndroidKeyToLinux.map(keyCode, event.metaState) ?: return super.onKeyDown(keyCode, event)
                WaylandSurface.nativeOnKeyEvent(linux, true, uptimeMs())
                return true
            }
            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                val linux = AndroidKeyToLinux.map(keyCode, event.metaState) ?: return super.onKeyUp(keyCode, event)
                WaylandSurface.nativeOnKeyEvent(linux, false, uptimeMs())
                return true
            }
        }.also { sv ->
            sv.isFocusable = true
            sv.isFocusableInTouchMode = true
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    val s = h.surface ?: return
                    WaylandSurface.nativeSurfaceCreated(s, resolutionPercent, scalePercent)
                    sv.post { sv.requestFocus() }
                }
                override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {
                    if (w <= 0 || h2 <= 0) return
                    surfaceW = w; surfaceH = h2
                    WaylandSurface.nativeOutputSizeChanged(w, h2, resolutionPercent, scalePercent)
                }
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    WaylandSurface.nativeSurfaceDestroyed()
                }
            })
        }

        addView(surfaceView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ))
        // Hide software cursor — touch = direct tablet mode
        WaylandSurface.nativeSetCursorVisible(false)
    }

    fun updateParams(rp: Int, sp: Int) {
        resolutionPercent = rp; scalePercent = sp
        if (surfaceW > 0 && surfaceH > 0)
            WaylandSurface.nativeOutputSizeChanged(surfaceW, surfaceH, rp, sp)
    }

    // ---- Touch input -------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val timeMs = uptimeMs()
        val idx    = event.actionIndex
        val x      = event.getX(idx)
        val y      = event.getY(idx)

        // Physical mouse — route as pointer events
        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            val (wx, wy) = toWaylandCoords(x, y)
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if ((event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0)
                        WaylandSurface.nativeOnPointerRightClick(wx, wy, timeMs)
                    else
                        WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_DOWN, timeMs)
                    true
                }
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_POINTER_MOVE, timeMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_UP, timeMs)
                    true
                }
                else -> true
            }
        }

        // Touch — tablet / direct mode
        val (wx, wy) = toWaylandCoords(x, y)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN         -> { WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_DOWN, timeMs); true }
            MotionEvent.ACTION_MOVE         -> { WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_MOVE, timeMs); true }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL       -> { WaylandSurface.nativeOnPointerEvent(wx, wy, WaylandSurface.ACTION_UP, timeMs); true }
            else -> true
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val timeMs = uptimeMs()
        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (v != 0f || h != 0f) WaylandSurface.nativeOnPointerAxis(-h, -v, timeMs)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- Coordinate mapping ------------------------------------------------

    /**
     * Map Android view coords → Wayland logical output coords.
     * The compositor's logical size = physical * rp% / scale, so we just
     * scale touch position by the same ratio.
     */
    private fun toWaylandCoords(viewX: Float, viewY: Float): Pair<Float, Float> {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val lw = (surfaceW * resolutionPercent / 100f / (scalePercent / 100f)).coerceAtLeast(1f)
        val lh = (surfaceH * resolutionPercent / 100f / (scalePercent / 100f)).coerceAtLeast(1f)
        val wx = (viewX / vw * lw).coerceIn(0f, lw - 0.5f)
        val wy = (viewY / vh * lh).coerceIn(0f, lh - 0.5f)
        return wx to wy
    }

    private fun uptimeMs() = (SystemClock.uptimeMillis() and 0x7FFF_FFFFL).toInt()
}

// ---------------------------------------------------------------------------
// Android KeyCode → Linux evdev key code
// Minimal set — covers typical desktop usage.  Extend as needed.
// ---------------------------------------------------------------------------

private object AndroidKeyToLinux {
    private val map = mapOf(
        // Letters
        KeyEvent.KEYCODE_A to 30, KeyEvent.KEYCODE_B to 48, KeyEvent.KEYCODE_C to 46,
        KeyEvent.KEYCODE_D to 32, KeyEvent.KEYCODE_E to 18, KeyEvent.KEYCODE_F to 33,
        KeyEvent.KEYCODE_G to 34, KeyEvent.KEYCODE_H to 35, KeyEvent.KEYCODE_I to 23,
        KeyEvent.KEYCODE_J to 36, KeyEvent.KEYCODE_K to 37, KeyEvent.KEYCODE_L to 38,
        KeyEvent.KEYCODE_M to 50, KeyEvent.KEYCODE_N to 49, KeyEvent.KEYCODE_O to 24,
        KeyEvent.KEYCODE_P to 25, KeyEvent.KEYCODE_Q to 16, KeyEvent.KEYCODE_R to 19,
        KeyEvent.KEYCODE_S to 31, KeyEvent.KEYCODE_T to 20, KeyEvent.KEYCODE_U to 22,
        KeyEvent.KEYCODE_V to 47, KeyEvent.KEYCODE_W to 17, KeyEvent.KEYCODE_X to 45,
        KeyEvent.KEYCODE_Y to 21, KeyEvent.KEYCODE_Z to 44,
        // Digits
        KeyEvent.KEYCODE_0 to 11, KeyEvent.KEYCODE_1 to 2,  KeyEvent.KEYCODE_2 to 3,
        KeyEvent.KEYCODE_3 to 4,  KeyEvent.KEYCODE_4 to 5,  KeyEvent.KEYCODE_5 to 6,
        KeyEvent.KEYCODE_6 to 7,  KeyEvent.KEYCODE_7 to 8,  KeyEvent.KEYCODE_8 to 9,
        KeyEvent.KEYCODE_9 to 10,
        // Special
        KeyEvent.KEYCODE_ENTER        to 28, KeyEvent.KEYCODE_SPACE       to 57,
        KeyEvent.KEYCODE_DEL          to 14, KeyEvent.KEYCODE_FORWARD_DEL to 111,
        KeyEvent.KEYCODE_TAB          to 15, KeyEvent.KEYCODE_ESCAPE       to 1,
        KeyEvent.KEYCODE_DPAD_UP      to 103,KeyEvent.KEYCODE_DPAD_DOWN   to 108,
        KeyEvent.KEYCODE_DPAD_LEFT    to 105,KeyEvent.KEYCODE_DPAD_RIGHT  to 106,
        KeyEvent.KEYCODE_PAGE_UP      to 104,KeyEvent.KEYCODE_PAGE_DOWN   to 109,
        KeyEvent.KEYCODE_MOVE_HOME    to 102,KeyEvent.KEYCODE_MOVE_END    to 107,
        KeyEvent.KEYCODE_CTRL_LEFT    to 29, KeyEvent.KEYCODE_CTRL_RIGHT  to 97,
        KeyEvent.KEYCODE_SHIFT_LEFT   to 42, KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
        KeyEvent.KEYCODE_ALT_LEFT     to 56, KeyEvent.KEYCODE_ALT_RIGHT   to 100,
        KeyEvent.KEYCODE_META_LEFT    to 125,KeyEvent.KEYCODE_META_RIGHT  to 126,
        KeyEvent.KEYCODE_CAPS_LOCK    to 58, KeyEvent.KEYCODE_NUM_LOCK    to 69,
        KeyEvent.KEYCODE_F1           to 59, KeyEvent.KEYCODE_F2          to 60,
        KeyEvent.KEYCODE_F3           to 61, KeyEvent.KEYCODE_F4          to 62,
        KeyEvent.KEYCODE_F5           to 63, KeyEvent.KEYCODE_F6          to 64,
        KeyEvent.KEYCODE_F7           to 65, KeyEvent.KEYCODE_F8          to 66,
        KeyEvent.KEYCODE_F9           to 67, KeyEvent.KEYCODE_F10         to 68,
        KeyEvent.KEYCODE_F11          to 87, KeyEvent.KEYCODE_F12         to 88,
        KeyEvent.KEYCODE_MINUS        to 12, KeyEvent.KEYCODE_EQUALS      to 13,
        KeyEvent.KEYCODE_LEFT_BRACKET to 26, KeyEvent.KEYCODE_RIGHT_BRACKET to 27,
        KeyEvent.KEYCODE_BACKSLASH    to 43, KeyEvent.KEYCODE_SEMICOLON   to 39,
        KeyEvent.KEYCODE_APOSTROPHE   to 40, KeyEvent.KEYCODE_GRAVE       to 41,
        KeyEvent.KEYCODE_COMMA        to 51, KeyEvent.KEYCODE_PERIOD      to 52,
        KeyEvent.KEYCODE_SLASH        to 53,
    )

    fun map(keyCode: Int, @Suppress("UNUSED_PARAMETER") meta: Int): Int? = map[keyCode]
}
