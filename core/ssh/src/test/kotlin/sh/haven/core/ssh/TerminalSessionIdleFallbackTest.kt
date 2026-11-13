package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * #482: picking a session from the remote-session list dropped the user into a
 * plain shell, and the attach command turned up in that shell some time later.
 *
 * The queued attach command is sent when a shell prompt is detected, and that
 * detection is a guess at the prompt's LAST CHARACTER (`$ # % > ❯`, plus
 * anything the user adds under #280). A prompt ending in anything else — a
 * powerline glyph, a bracket, a non-Latin character — is never recognised, so
 * the command sat in the queue until some unrelated later output happened to
 * end in one of those characters. That is exactly "attached to the normal
 * shell, then the command appears far too late".
 *
 * The fix does not try to enumerate more prompt characters, which cannot be
 * won. It waits for the shell to go quiet instead: a shell that has printed
 * something and then stopped is a shell waiting for input, whatever its prompt
 * happens to look like.
 */
class TerminalSessionIdleFallbackTest {

    private fun shellOf(channel: ChannelShell) = ShellChannel(
        input = channel.inputStream,
        output = channel.outputStream,
        resizeFn = { c, r -> channel.setPtySize(c, r, 0, 0) },
        disconnectFn = { channel.disconnect() },
        connectedProbe = { channel.isConnected },
        closedProbe = { channel.isClosed },
        exitStatusProbe = { channel.exitStatus },
    )

    private fun session(out: ByteArrayOutputStream, pipeIn: PipedInputStream, cmds: List<String>): TerminalSession {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns pipeIn
            every { getOutputStream() } returns out
            every { isConnected } returns true
        }
        return TerminalSession(
            sessionId = "idle-fallback",
            profileId = "test",
            label = "user@host",
            shell = shellOf(channel),
            client = mockk<SshClient>(relaxed = true),
            onDataReceived = { _, _, _ -> },
            pendingCommands = cmds,
        )
    }

    /**
     * The reported case. A prompt ending in a powerline separator matches none
     * of the built-in terminators, so the prompt path never fires — the command
     * must still reach the shell once it falls silent.
     */
    @Test
    fun `an unrecognised prompt still gets the queued command once the shell goes quiet`() {
        val out = ByteArrayOutputStream()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val session = session(out, pipeIn, listOf("zellij attach work --create"))
        try {
            session.start()
            pipeOut.write("[32muser@host[0m  ".toByteArray())
            pipeOut.flush()

            // Before the shell has been quiet long enough, nothing is sent —
            // firing mid-prompt would interleave with a prompt still painting.
            Thread.sleep(400)
            assertTrue(
                "command fired too early, before the shell settled: '${String(out.toByteArray())}'",
                !String(out.toByteArray()).contains("zellij attach"),
            )

            // Once it has gone quiet, the command goes out even though no
            // recognised prompt character was ever seen.
            Thread.sleep(1400)
            assertTrue(
                "a prompt ending in a powerline glyph is not in promptTerminators, so the " +
                    "queued attach command would wait indefinitely for unrelated output to " +
                    "end in \$ # % > ❯ — that is #482. Got: '${String(out.toByteArray())}'",
                String(out.toByteArray()).contains("zellij attach work --create"),
            )
        } finally {
            session.close()
        }
    }

    /**
     * The fallback must not pre-empt the prompt path when the prompt IS
     * recognised — that path is immediate and should stay that way, or every
     * ordinary connect gains a needless delay.
     */
    @Test
    fun `a recognised prompt still fires immediately, not after the idle delay`() {
        val out = ByteArrayOutputStream()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val session = session(out, pipeIn, listOf("tmux new-session -A -s work"))
        try {
            session.start()
            pipeOut.write("user@host:~$ ".toByteArray())
            pipeOut.flush()
            Thread.sleep(300) // well under the idle window
            assertTrue(
                "a '$' prompt must fire the queued command immediately, not wait for the " +
                    "idle fallback. Got: '${String(out.toByteArray())}'",
                String(out.toByteArray()).contains("tmux new-session -A -s work"),
            )
        } finally {
            session.close()
        }
    }

    /**
     * Output that keeps arriving means the shell is still working — a long MOTD
     * or a slow login banner must not be mistaken for a prompt, or the command
     * lands in the middle of it.
     */
    @Test
    fun `continuing output defers the fallback instead of firing into it`() {
        val out = ByteArrayOutputStream()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val session = session(out, pipeIn, listOf("zellij attach work --create"))
        try {
            session.start()
            // A banner still printing, in chunks closer together than the window.
            repeat(5) {
                pipeOut.write("motd line $it of a long login banner\n".toByteArray())
                pipeOut.flush()
                Thread.sleep(500)
            }
            assertTrue(
                "the fallback fired while output was still arriving — the timer must measure " +
                    "silence since the LAST byte, not since the first. Got: '${String(out.toByteArray())}'",
                !String(out.toByteArray()).contains("zellij attach"),
            )
            // Banner over; now it goes quiet and the command goes out.
            Thread.sleep(1400)
            assertTrue(
                "command never arrived after the banner finished: '${String(out.toByteArray())}'",
                String(out.toByteArray()).contains("zellij attach work --create"),
            )
        } finally {
            session.close()
        }
    }
}
