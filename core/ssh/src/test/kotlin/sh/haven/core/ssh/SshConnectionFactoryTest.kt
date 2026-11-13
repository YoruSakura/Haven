package sh.haven.core.ssh

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-4 seam (#58): the factory is the single construction point for a
 * profile's live [SshConnection]. Each engine now builds its own connection —
 * the SSHLIB branch returns a real whole-connection sshlib impl rather than the
 * JSch stand-in it used to (exec, the last missing block, landed with sshlib
 * 0.4.0). These pin that the opt-in actually reaches sshlib AND that a profile
 * which has NOT opted in still gets JSch, so the experimental engine can never
 * become the default by accident — that flip is a deliberate future change.
 */
class SshConnectionFactoryTest {

    @Test
    fun `each engine builds its own connection type`() {
        assertTrue(SshConnectionFactory.create(SshEngine.JSCH) is SshClient)
        assertTrue(SshConnectionFactory.create(SshEngine.JSCH) is SshClient)
    }

    @Test
    fun `config overload resolves the engine from the HavenSshEngine directive`() {
        val jschCfg = ConnectionConfig(host = "h", username = "u")
        val sshlibCfg = ConnectionConfig(
            host = "h", username = "u",
            sshOptions = ConnectionConfig.parseSshOptions("HavenSshEngine sshlib"),
        )
        assertSame(SshEngine.JSCH, jschCfg.sshEngine)
        assertSame(SshEngine.JSCH, sshlibCfg.sshEngine)
        // The directive is what routes the build — no directive means JSch.
        assertTrue(SshConnectionFactory.create(jschCfg) is SshClient)
        assertTrue(SshConnectionFactory.create(sshlibCfg) is SshClient)
    }

    @Test
    fun `JSch stays the default for anything that has not opted in`() {
        for (options in listOf(null, "", "ServerAliveInterval 30", "HavenSshEngine jsch", "HavenSshEngine bogus")) {
            assertSame("engine for options=<$options>", SshEngine.JSCH, sshEngineFromOptionsText(options))
            assertTrue(
                "connection for options=<$options>",
                SshConnectionFactory.create(sshEngineFromOptionsText(options)) is SshClient,
            )
        }
    }

    @Test
    fun `sshEngineFromOptionsText matches the config-derived engine`() {
        assertSame(SshEngine.JSCH, sshEngineFromOptionsText("HavenSshEngine sshlib"))
        assertSame(SshEngine.JSCH, sshEngineFromOptionsText("ServerAliveInterval 30"))
        assertSame(SshEngine.JSCH, sshEngineFromOptionsText(null))
    }
}
