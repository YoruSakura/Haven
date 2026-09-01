package sh.haven.feature.terminal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.connectbot.terminal.AgentLine
import org.connectbot.terminal.AgentSnapshot
import org.connectbot.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.feature.terminal.agent.TerminalSessionRegistry

/** Pins the native-handle ordering used when a local PRoot process exits. */
class LocalTerminalDisposalTest {

    @Test
    fun `exit unregisters and snapshots the terminal before closing its native handle`() {
        val emulator = mockk<TerminalEmulator>(relaxed = true) {
            every { buildAgentSnapshot(any(), any()) } returns AgentSnapshot(
                rows = 1,
                cols = 80,
                cursorRow = 0,
                cursorCol = 4,
                cursorVisible = true,
                terminalTitle = "",
                scrollbackSize = 0,
                lines = listOf(
                    AgentLine(
                        text = "exit",
                        softWrapped = false,
                        semanticSegments = emptyList(),
                    ),
                ),
            )
        }
        val registry = TerminalSessionRegistry().apply { register("old-session", emulator) }

        unregisterAndCloseTerminal(
            sessionId = "old-session",
            profileId = "ubuntu-profile",
            label = "Ubuntu",
            emulator = emulator,
            registry = registry,
        )

        assertNull(registry.get("old-session"))
        assertEquals(listOf("exit"), registry.lastExited("ubuntu-profile")?.screen)
        verifyOrder {
            emulator.buildAgentSnapshot(false, Int.MAX_VALUE)
            emulator.close()
        }
    }
}
