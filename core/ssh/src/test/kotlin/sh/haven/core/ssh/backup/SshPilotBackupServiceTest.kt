package sh.haven.core.ssh.backup

import androidx.room.withTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.HavenDatabase
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import javax.crypto.AEADBadTagException

/** Exercise real backup decoding and service writes against an in-memory repository fixture. */
class SshPilotBackupServiceTest {
    private val database = mockk<HavenDatabase>()
    private val connections = mockk<ConnectionRepository>()
    private val groups = mockk<ConnectionGroupDao>()
    private val keys = mockk<SshKeyRepository>()
    private val profiles = linkedMapOf<String, ConnectionProfile>()
    private val groupRows = linkedMapOf<String, ConnectionGroup>()
    private val keyRows = linkedMapOf<String, SshKey>()
    private val passphrases = mutableMapOf<String, String?>()
    private val password = "备份密码🔒-not-a-real-password"
    private val service = SshPilotBackupService(database, connections, groups, keys, mockk<SshIdentityRepository>())
    private fun fixture(name: String) = javaClass.getResourceAsStream("/sshpilot/$name")!!.use { it.readBytes() }

    @Before fun setup() {
        // Room's actual transaction/rollback contract is unchanged; run the service block directly here.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            secondArg<suspend () -> Any?>().invoke()
        }
        coEvery { connections.getAll() } answers { profiles.values.toList() }
        coEvery { connections.save(any()) } answers { firstArg<ConnectionProfile>().let { profiles[it.id] = it } }
        coEvery { groups.getAll() } answers { groupRows.values.toList() }
        coEvery { groups.upsert(any()) } answers { firstArg<ConnectionGroup>().let { groupRows[it.id] = it } }
        coEvery { keys.getAll() } answers { keyRows.values.toList() }
        coEvery { keys.save(any()) } answers { firstArg<SshKey>().let { keyRows[it.id] = it } }
        coEvery { keys.setStoredPassphrase(any(), any()) } answers { passphrases[firstArg()] = secondArg() }
    }

    @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    @Test fun `encrypted backups add then merge or overwrite credentials without deleting other records`() = runTest {
        val first = service.import(fixture("python-special.yaml"), password)
        assertEquals(3, first.servers)
        assertEquals(3, first.keys)
        val original = profiles.toMap()
        val sourceKeyId = passphrases.entries.single { it.value != null }.key
        val sourcePassphrase = passphrases.getValue(sourceKeyId)
        profiles.replaceAll { _, p -> p.copy(sshPassword = "phone-password") }
        passphrases[sourceKeyId] = "phone-passphrase"
        val unrelated = ConnectionProfile(id = "phone-only", label = "phone-only", host = "local.example.test", username = "alice")
        profiles[unrelated.id] = unrelated

        val merged = service.import(fixture("python-encrypted.spbk"), password)
        assertEquals(0, merged.servers)
        assertEquals(3, merged.preserved)
        assertEquals(0, merged.updatedServers)
        assertEquals("phone-passphrase", passphrases[sourceKeyId])
        assertTrue(original.keys.all { profiles.getValue(it).sshPassword == "phone-password" })

        val overwritten = service.import(fixture("python-encrypted.spbk"), password, overwriteExisting = true)
        assertEquals(0, overwritten.servers)
        assertEquals(0, overwritten.keys)
        assertEquals(0, overwritten.groups)
        assertEquals(3, overwritten.updatedServers)
        assertEquals(3, overwritten.updatedKeys)
        assertEquals(groupRows.size, overwritten.updatedGroups)
        assertEquals(0, overwritten.preserved)
        assertEquals(original, profiles.filterKeys { it in original })
        assertEquals(unrelated, profiles[unrelated.id])
        assertEquals(sourcePassphrase, passphrases[sourceKeyId])
    }

    @Test fun `wrong password in overwrite mode never reads or writes repositories`() = runTest {
        try {
            service.import(fixture("python-encrypted.spbk"), "wrong-password", overwriteExisting = true)
            fail("Wrong-password import must fail")
        } catch (_: AEADBadTagException) {
            coVerify(exactly = 0) { connections.getAll() }
            coVerify(exactly = 0) { connections.save(any()) }
            coVerify(exactly = 0) { groups.upsert(any()) }
            coVerify(exactly = 0) { keys.save(any()) }
        }
    }
}
