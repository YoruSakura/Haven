package sh.haven.core.ssh.backup

import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.data.db.entities.SshKey
import java.util.UUID

/** A pure merge plan keeps collisions and reference remapping testable before persistence. */
internal object SshPilotServerMerge {
    data class Merge(val plan: SshPilotServerMapper.Plan, val preserved: Int,
        val updatedServers: Int = 0, val updatedKeys: Int = 0, val updatedGroups: Int = 0)

    fun merge(plan: SshPilotServerMapper.Plan, existing: List<ConnectionProfile>,
        groups: List<ConnectionGroup>, keys: List<SshKey>, overwriteExisting: Boolean = false): Merge {
        val profilesById = existing.associateBy { it.id }
        val groupsById = groups.associateBy { it.id }
        val keysById = keys.associateBy { it.id }
        val groupMap = plan.groups.associate { g ->
            g.id to ((groupsById[g.id] ?: groups.firstOrNull { it.label == g.label })?.id ?: g.id)
        }
        val keyMap = plan.keys.associate { k ->
            val sameKey: (SshKey) -> Boolean = { it.fingerprintSha256 == k.fingerprintSha256 && it.keyType == k.keyType }
            val match = keysById[k.id]?.takeIf(sameKey) ?: keys.firstOrNull(sameKey)
            k.id to (match?.id ?: if (k.id in keysById) UUID.randomUUID().toString() else k.id)
        }
        val profileMap = plan.connections.associate { p ->
            val match = profilesById[p.id]?.takeIf { it.connectionType == "SSH" }
                ?: existing.firstOrNull { it.connectionType == "SSH" && it.label == p.label &&
                    it.host == p.host && it.port == p.port && it.username == p.username }
            p.id to (match?.id ?: if (p.id in profilesById) UUID.randomUUID().toString() else p.id)
        }
        val matchedServers = plan.connections.count { profileMap[it.id] in profilesById }
        val profilesToWrite = plan.connections.filter { overwriteExisting || profileMap[it.id] !in profilesById }.map { p ->
            val mapped = p.copy(id = profileMap.getValue(p.id),
                groupId = p.groupId?.let { groupMap[it] ?: it },
                keyId = p.keyId?.let { keyMap[it] ?: it },
                jumpProfileId = p.jumpProfileId?.let { profileMap[it] ?: it },
                // Imported credentials must also win over a group's shared identity.
                identityId = SshIdentity.NONE_ID,
                authMethods = p.authMethodSpecs.joinToString("\n") { method ->
                    if (method is ConnectionProfile.AuthMethodSpec.Key) method.copy(keyId = keyMap[method.keyId] ?: method.keyId).serialize()
                    else method.serialize()
                })
            // Keep Android-only settings (VPN, proxy, screen options, etc.) on matched rows.
            profilesById[mapped.id]?.copy(label = mapped.label, host = mapped.host, port = mapped.port,
                username = mapped.username, sshPassword = mapped.sshPassword, keyId = mapped.keyId,
                authType = mapped.authType, authMethods = mapped.authMethods, ignoreSavedKeys = mapped.ignoreSavedKeys,
                groupId = mapped.groupId, identityId = mapped.identityId, sortOrder = mapped.sortOrder,
                jumpProfileId = mapped.jumpProfileId, forwardAgent = mapped.forwardAgent,
                remoteCommand = mapped.remoteCommand, requestPty = mapped.requestPty, sshOptions = mapped.sshOptions) ?: mapped
        }
        val groupsToWrite = plan.groups.filter { overwriteExisting || groupMap[it.id] !in groupsById }.map { g ->
            val targetId = groupMap.getValue(g.id)
            groupsById[targetId]?.copy(label = g.label, sortOrder = g.sortOrder) ?: g.copy(id = targetId)
        }.distinctBy { it.id }
        val keysToWrite = plan.keys.mapNotNull { k ->
            val targetId = keyMap.getValue(k.id)
            val local = keysById[targetId]
            when {
                local == null -> k.copy(id = targetId)
                // A portable key must never replace a device-bound private key.
                !overwriteExisting || local.hardwareAlias != null -> null
                else -> k.copy(id = targetId, createdAt = local.createdAt,
                    biometricProtected = local.biometricProtected, enabledForAuth = local.enabledForAuth,
                    certificateBytes = local.certificateBytes, caConfigId = local.caConfigId, certIssuedAt = local.certIssuedAt)
            }
        }.distinctBy { it.id }
        val writtenKeyIds = keysToWrite.map { it.id }.toSet()
        val passphrases = plan.passphrases.mapNotNull { (sourceId, passphrase) ->
            keyMap[sourceId]?.takeIf { it in writtenKeyIds }?.let { it to passphrase }
        }.toMap()
        return Merge(plan.copy(connections = profilesToWrite, groups = groupsToWrite,
            keys = keysToWrite, passphrases = passphrases),
            preserved = if (overwriteExisting) 0 else matchedServers,
            updatedServers = if (overwriteExisting) matchedServers else 0,
            updatedKeys = keysToWrite.count { it.id in keysById },
            updatedGroups = groupsToWrite.count { it.id in groupsById })
    }
}
