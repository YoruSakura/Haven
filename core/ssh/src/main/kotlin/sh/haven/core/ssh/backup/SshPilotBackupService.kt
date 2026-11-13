package sh.haven.core.ssh.backup

import androidx.room.withTransaction
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.HavenDatabase
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshPilotBackupService @Inject constructor(
    private val database: HavenDatabase,
    private val connections: ConnectionRepository,
    private val groups: ConnectionGroupDao,
    private val keys: SshKeyRepository,
    private val identities: SshIdentityRepository,
) {
    data class Result(val servers: Int, val keys: Int, val groups: Int, val preserved: Int = 0,
        val notices: Set<String> = emptySet(), val updatedServers: Int = 0,
        val updatedKeys: Int = 0, val updatedGroups: Int = 0)
    data class Export(val data: ByteArray, val result: Result)

    suspend fun import(data: ByteArray, password: String, overwriteExisting: Boolean = false): Result {
        // Decrypt, validate all targets and parse keys before touching the database.
        val plan = SshPilotServerMapper.plan(SshPilotBackupCodec.read(data, password))
        return database.withTransaction {
            val merge = SshPilotServerMerge.merge(plan, connections.getAll(), groups.getAll(), keys.getAll(), overwriteExisting)
            merge.plan.groups.forEach { groups.upsert(it) }
            merge.plan.keys.forEach {
                keys.save(it)
                // Clear an obsolete local passphrase too when the backup omits it.
                keys.setStoredPassphrase(it.id, merge.plan.passphrases[it.id])
            }
            merge.plan.connections.forEach { connections.save(it) }
            Result(servers = merge.plan.connections.size - merge.updatedServers,
                keys = merge.plan.keys.size - merge.updatedKeys, groups = merge.plan.groups.size - merge.updatedGroups,
                preserved = merge.preserved, notices = plan.notices, updatedServers = merge.updatedServers,
                updatedKeys = merge.updatedKeys, updatedGroups = merge.updatedGroups)
        }
    }

    suspend fun export(password: String): Export {
        val profiles = connections.getAll().map { identities.applyTo(it) }
        val decrypted = keys.getAllDecryptedDetailed()
        val notices = linkedSetOf<String>()
        if (decrypted.hardware.isNotEmpty()) notices += "hardware"
        if (decrypted.declined.isNotEmpty() ||
            decrypted.keys.size + decrypted.hardware.size < keys.getAll().size) notices += "keys"
        val allGroups = groups.getAll()
        val passphrases = decrypted.keys.mapNotNull { key -> keys.getStoredPassphrase(key.id)?.let { key.id to it } }.toMap()
        val manifest = SshPilotServerMapper.manifest(profiles, allGroups, decrypted.keys, passphrases, notices)
        val data = SshPilotBackupCodec.write(manifest, password)
        // The exact bytes to be written must decrypt to the same portable document.
        check(SshPilotBackupCodec.read(data, password) == manifest) { "Backup verification failed" }
        return Export(data, Result(profiles.count { it.connectionType == "SSH" },
            (manifest["private_keys"] as List<*>).size,
            ((manifest["connection_store"] as Map<*, *>)["groups"] as List<*>).size, notices = notices))
    }
}
