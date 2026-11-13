package sh.haven.core.ssh

/** Creates the supported SSH engine, including for imported profiles. */
object SshConnectionFactory {
    fun create(engine: SshEngine): SshConnection = SshClient()
    fun create(config: ConnectionConfig): SshConnection = SshClient()
}
