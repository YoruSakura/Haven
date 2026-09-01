package sh.haven.core.wayland

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaylandPasteSenderTest {
    private data class EvdevEvent(val key: Int, val pressed: Int)

    private val reportedPaste = """cp /etc/apt/sources.list.d/ubuntu.sources \
   /etc/apt/sources.list.d/ubuntu.sources.bak

cat > /etc/apt/sources.list.d/ubuntu.sources <<'EOF'
Types: deb
URIs: https://mirrors.ustc.edu.cn/ubuntu-ports
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://mirrors.ustc.edu.cn/ubuntu-ports
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF"""

    @Test
    fun `reported long paste is delivered completely in bounded bursts`() = runTest {
        val events = mutableListOf<EvdevEvent>()
        val burstSizes = mutableListOf<Int>()
        var currentBurstSize = 0
        val sender = WaylandPasteSender(
            sendKey = { key, pressed ->
                events += EvdevEvent(key, pressed)
                currentBurstSize++
            },
            pauseForDrain = {
                burstSizes += currentBurstSize
                currentBurstSize = 0
            },
        )

        val sent = sender.send(reportedPaste)
        val expectedEvents = reportedPaste.mapNotNull { ch ->
            val (key, shift) = charToEvdevWithShift(ch)
            if (key < 0) null else if (shift) {
                listOf(
                    EvdevEvent(42, 1),
                    EvdevEvent(key, 1), EvdevEvent(key, 0),
                    EvdevEvent(42, 0),
                )
            } else {
                listOf(EvdevEvent(key, 1), EvdevEvent(key, 0))
            }
        }.flatten()

        assertEquals(reportedPaste.length, sent)
        assertEquals(expectedEvents, events)
        assertTrue(burstSizes.isNotEmpty())
        assertTrue(burstSizes.all { it in 0..32 })
        assertEquals(events.size, burstSizes.sum())
    }

    @Test
    fun `shifted characters respect event budget before another burst`() = runTest {
        val completedBurstSizes = mutableListOf<Int>()
        var currentBurstSize = 0
        val sender = WaylandPasteSender(
            sendKey = { _, _ -> currentBurstSize++ },
            pauseForDrain = {
                completedBurstSizes += currentBurstSize
                currentBurstSize = 0
            },
            maxEventsPerBurst = 8,
        )

        assertEquals(4, sender.send("ABCD"))
        assertEquals(listOf(0, 8, 8), completedBurstSizes)
    }

    @Test
    fun `unsupported characters are skipped without an unmatched event`() = runTest {
        val events = mutableListOf<EvdevEvent>()
        val sender = WaylandPasteSender(
            sendKey = { key, pressed -> events += EvdevEvent(key, pressed) },
            pauseForDrain = {},
        )

        assertEquals(2, sender.send("a中b"))
        assertEquals(
            listOf(
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(48, 1), EvdevEvent(48, 0),
            ),
            events,
        )
    }

    @Test
    fun `empty paste does not schedule a drain pause`() = runTest {
        var pauses = 0
        val sender = WaylandPasteSender(
            sendKey = { _, _ -> error("empty paste emitted a key") },
            pauseForDrain = { pauses++ },
        )

        assertEquals(0, sender.send(""))
        assertEquals(0, pauses)
    }
}
