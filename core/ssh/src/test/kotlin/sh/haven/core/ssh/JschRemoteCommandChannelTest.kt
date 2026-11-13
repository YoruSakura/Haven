package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * JSch contract for the profile remote-command terminal channel (#436): a
 * non-null command opens an SSH `exec` request for it (the RemoteCommand path
 * — runs before login-shell startup files, so `tmux new -A -s x` attaches
 * without racing a `.bashrc` auto-tmux hook); null/blank falls back to an
 * interactive shell.
 *
 * Verified against a real MINA sshd whose CommandFactory echoes back the
 * command it received and whose ShellFactory writes a distinct banner — so the
 * bytes the client reads prove which path the server actually took, not a mock.
 *
 * JSch-specific by design: the exec channel is only implemented on the JSch
 * engine today (`SshEngine.SSHLIB` still builds a JSch connection, see
 * [SshConnectionFactory]); a sshlib `openTerminalChannel` is future #58 work,
 * so this is not in the engine-agnostic [ShellChannelContractTest].
 */
class JschRemoteCommandChannelTest {

    /** A hung read is a failure, not a wedged CI run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var server: SshServer
    private var serverPort: Int = 0
    private var client: SshClient? = null

    @Before
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("remotecmd-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            commandFactory = CommandFactory { _, command -> EchoCommand(command) }
            shellFactory = ShellFactory { BannerShell() }
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun tearDown() {
        try { client?.close() } catch (_: Exception) { /* best effort */ }
        client = null
        if (::server.isInitialized) server.stop(true)
    }

    private fun connectedClient(): SshClient =
        client ?: SshClient().also { c ->
            client = c
            runBlocking {
                c.connect(
                    ConnectionConfig(
                        host = "127.0.0.1",
                        port = serverPort,
                        username = "tester",
                        authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                    ),
                )
            }
        }

    /** Read from [input] until [needle] appears or the test timeout fires. */
    private fun readUntil(input: InputStream, needle: String): String {
        val seen = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return seen.toString(Charsets.UTF_8.name())
            if (n > 0) {
                seen.write(buf, 0, n)
                val s = seen.toString(Charsets.UTF_8.name())
                if (s.contains(needle)) return s
            }
        }
    }

    @Test
    fun `remote command runs via an exec request`() {
        val shell = connectedClient().openTerminalChannel(
            remoteCommand = "run-marker-alpha",
            requestPty = false,
            term = "xterm",
            cols = 80,
            rows = 24,
        )
        val out = readUntil(shell.input, "GOT:")
        assertTrue("server did not exec the remote command: $out", out.contains("GOT:run-marker-alpha"))
        shell.disconnect()
    }

    @Test
    fun `remote command with a PTY runs via exec (the tmux attach case)`() {
        val shell = connectedClient().openTerminalChannel(
            remoteCommand = "tmux new -A -s work",
            requestPty = true,
            term = "xterm",
            cols = 80,
            rows = 24,
        )
        val out = readUntil(shell.input, "GOT:")
        assertTrue("server did not exec the PTY remote command: $out", out.contains("GOT:tmux new -A -s work"))
        shell.disconnect()
    }

    @Test
    fun `null command falls back to an interactive shell`() {
        val shell = connectedClient().openTerminalChannel(
            remoteCommand = null,
            requestPty = true,
            term = "xterm",
            cols = 80,
            rows = 24,
        )
        val out = readUntil(shell.input, BANNER)
        assertTrue("expected shell banner, got: $out", out.contains(BANNER))
        shell.disconnect()
    }

    @Test
    fun `blank command falls back to an interactive shell`() {
        val shell = connectedClient().openTerminalChannel(
            remoteCommand = "   ",
            requestPty = true,
            term = "xterm",
            cols = 80,
            rows = 24,
        )
        val out = readUntil(shell.input, BANNER)
        assertTrue("expected shell banner for blank command, got: $out", out.contains(BANNER))
        shell.disconnect()
    }

    private companion object {
        const val BANNER = "BANNER-SHELL\n"

        /** exec request → echoes back the exact command the server received. */
        private class EchoCommand(private val command: String) : Command {
            private var out: OutputStream? = null
            private var exit: ExitCallback? = null

            @Volatile private var worker: Thread? = null

            override fun setInputStream(value: InputStream?) {}
            override fun setOutputStream(value: OutputStream?) { out = value }
            override fun setErrorStream(value: OutputStream?) {}
            override fun setExitCallback(value: ExitCallback?) { exit = value }

            override fun start(channel: ChannelSession?, env: Environment?) {
                worker = Thread({
                    try {
                        out!!.write("GOT:$command\n".toByteArray()); out!!.flush()
                        exit!!.onExit(0)
                    } catch (_: Exception) {
                        // channel torn down under us — fine
                    }
                }, "echo-command").apply { isDaemon = true; start() }
            }

            override fun destroy(channel: ChannelSession?) { worker?.interrupt() }
        }

        /** shell request → writes a distinct banner, then drains stdin until closed. */
        private class BannerShell : Command {
            private var out: OutputStream? = null
            private var input: InputStream? = null
            private var exit: ExitCallback? = null

            @Volatile private var worker: Thread? = null

            override fun setInputStream(value: InputStream?) { input = value }
            override fun setOutputStream(value: OutputStream?) { out = value }
            override fun setErrorStream(value: OutputStream?) {}
            override fun setExitCallback(value: ExitCallback?) { exit = value }

            override fun start(channel: ChannelSession?, env: Environment?) {
                worker = Thread({
                    try {
                        out!!.write(BANNER.toByteArray()); out!!.flush()
                        while (input!!.read() >= 0) { /* keep channel open until torn down */ }
                        exit!!.onExit(0)
                    } catch (_: Exception) {
                        // channel torn down under us — fine
                    }
                }, "banner-shell").apply { isDaemon = true; start() }
            }

            override fun destroy(channel: ChannelSession?) { worker?.interrupt() }
        }
    }
}
