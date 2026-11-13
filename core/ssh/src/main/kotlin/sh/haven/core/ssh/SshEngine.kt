package sh.haven.core.ssh

/** The supported SSH engine. Imported legacy engine options resolve to JSch. */
enum class SshEngine { JSCH }

fun sshEngineFrom(sshOptions: Map<String, String>): SshEngine = SshEngine.JSCH
fun sshEngineFromOptionsText(sshOptions: String?): SshEngine = SshEngine.JSCH
val ConnectionConfig.sshEngine: SshEngine get() = SshEngine.JSCH
