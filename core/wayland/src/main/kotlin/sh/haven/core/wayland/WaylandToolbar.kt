package sh.haven.core.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.toolbar.KeyboardToolbar

/** Evdev keycodes for vterm key constants used by the terminal toolbar. */
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_HOME = 11
private const val VTERM_KEY_END = 12
private const val VTERM_KEY_PAGEUP = 13
private const val VTERM_KEY_PAGEDOWN = 14
private const val VTERM_KEY_INS = 9
private const val VTERM_KEY_DEL = 10
private const val VTERM_KEY_FUNCTION_0 = 256
private const val PASTE_MAX_EVENTS_PER_BURST = 32
private const val PASTE_DRAIN_DELAY_MS = 16L

private fun vtermKeyToEvdev(key: Int): Int = when (key) {
    VTERM_KEY_UP -> 103
    VTERM_KEY_DOWN -> 108
    VTERM_KEY_LEFT -> 105
    VTERM_KEY_RIGHT -> 106
    VTERM_KEY_HOME -> 102
    VTERM_KEY_END -> 107
    VTERM_KEY_PAGEUP -> 104
    VTERM_KEY_PAGEDOWN -> 109
    VTERM_KEY_DEL -> 111
    VTERM_KEY_INS -> 110
    in (VTERM_KEY_FUNCTION_0 + 1)..(VTERM_KEY_FUNCTION_0 + 12) -> {
        val fn = key - VTERM_KEY_FUNCTION_0 // 1-12
        if (fn <= 10) 58 + fn else 85 + fn // F1=59..F10=68, F11=87, F12=88
    }
    else -> -1
}

/**
 * Wraps the shared [KeyboardToolbar] for the Wayland desktop.
 * Translates the terminal's byte/vterm callbacks into evdev keycodes
 * sent via [WaylandBridge.nativeSendKey].
 */
@Composable
fun WaylandToolbar(
    layout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: NavBlockMode = NavBlockMode.ALIGNED,
    uniformGrid: Boolean = false,
    pasteText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    KeyboardToolbar(
        onSendBytes = { bytes ->
            when {
                bytes.contentEquals(byteArrayOf(0x1b)) -> sendEvdevPress(1) // Esc
                bytes.contentEquals(byteArrayOf(0x09)) -> sendEvdevPress(15) // Tab
                // Ctrl+letter (0x01-0x1a) — Ctrl already held via toggle
                bytes.size == 1 && bytes[0] in 1..26 -> {
                    val ch = 'a' + (bytes[0].toInt() - 1)
                    sendCharAsEvdev(ch)
                }
                else -> String(bytes, Charsets.UTF_8).forEach { ch -> sendCharAsEvdev(ch) }
            }
        },
        onDispatchKey = { _, key ->
            val evdev = vtermKeyToEvdev(key)
            if (evdev > 0) sendEvdevPress(evdev)
        },
        focusRequester = focusRequester,
        ctrlActive = ctrlActive,
        altActive = altActive,
        layout = layout,
        navBlockMode = navBlockMode,
        uniformGrid = uniformGrid,
        onToggleCtrl = {
            ctrlActive = !ctrlActive
            WaylandBridge.nativeSendKey(29, if (ctrlActive) 1 else 0)
        },
        onToggleAlt = {
            altActive = !altActive
            WaylandBridge.nativeSendKey(56, if (altActive) 1 else 0)
        },
        onPaste = pasteText,
        modifier = modifier,
    )
}

/**
 * One shared, serialized paste path for both the toolbar button and hardware
 * Ctrl+Shift+V. Sharing the mutex prevents simultaneous entry points from
 * filling the native queue independently.
 */
@Composable
internal fun rememberWaylandPasteHandler(): (String) -> Unit {
    val pasteScope = rememberCoroutineScope()
    val pasteMutex = remember { Mutex() }
    val pasteSender = remember {
        WaylandPasteSender(
            sendKey = WaylandBridge::nativeSendKey,
            pauseForDrain = { delay(PASTE_DRAIN_DELAY_MS) },
        )
    }
    return remember(pasteScope, pasteMutex, pasteSender) {
        { text ->
            // The native JNI queue holds only 255 pending events and silently
            // drops overflow. A synchronous paste can therefore lose the tail
            // and, worse, retain a final DOWN after its UP was dropped. Pace
            // bounded bursts off the UI thread and serialize repeated pastes.
            pasteScope.launch {
                pasteMutex.withLock {
                    pasteSender.send(text)
                }
            }
        }
    }
}

/**
 * Types clipboard text without overflowing the compositor's fixed JNI queue.
 *
 * Each mapped character costs two events, or four when Shift is required.
 * Keeping a burst far below the queue's 255 usable slots leaves headroom for
 * touch/hardware input and prevents a key DOWN from being separated from UP.
 */
internal class WaylandPasteSender(
    private val sendKey: (evdev: Int, pressed: Int) -> Unit,
    private val pauseForDrain: suspend () -> Unit,
    private val maxEventsPerBurst: Int = PASTE_MAX_EVENTS_PER_BURST,
) {
    init {
        require(maxEventsPerBurst >= 4) { "paste event budget must fit a shifted key tap" }
    }

    /** Returns the number of mapped characters accepted for delivery. */
    suspend fun send(text: String): Int {
        if (text.isEmpty()) return 0

        var burstEvents = 0
        var sentCharacters = 0
        // Let any key/touch events immediately preceding Paste leave the queue.
        pauseForDrain()
        for (ch in text) {
            val (evdev, needsShift) = charToEvdevWithShift(ch)
            if (evdev < 0) continue
            val eventCount = if (needsShift) 4 else 2
            if (burstEvents + eventCount > maxEventsPerBurst) {
                pauseForDrain()
                burstEvents = 0
            }
            if (needsShift) sendKey(PASTE_LEFT_SHIFT, 1)
            sendKey(evdev, 1)
            sendKey(evdev, 0)
            if (needsShift) sendKey(PASTE_LEFT_SHIFT, 0)
            burstEvents += eventCount
            sentCharacters++
        }
        // Keep the mutex until the final burst has had a chance to drain.
        if (burstEvents > 0) pauseForDrain()
        return sentCharacters
    }
}

private const val PASTE_LEFT_SHIFT = 42

private fun sendEvdevPress(evdev: Int) {
    WaylandBridge.nativeSendKey(evdev, 1)
    WaylandBridge.nativeSendKey(evdev, 0)
}
