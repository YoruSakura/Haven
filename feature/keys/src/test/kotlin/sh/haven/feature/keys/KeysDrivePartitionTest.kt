package sh.haven.feature.keys

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.db.entities.SshKey

/**
 * The Keys screen groups the ephemeral USB drive VM keys under their own
 * collapsed section. The split is by the stable mint label — the same marker
 * `UsbDriveVmManager` uses to keep those keys out of the try-every-key auth
 * pool — so renaming one deliberately promotes it to the user section.
 */
class KeysDrivePartitionTest {

    private fun key(id: String, label: String) = SshKey(
        id = id, label = label, keyType = "ssh-ed25519",
        privateKeyBytes = ByteArray(0), publicKeyOpenSsh = "", fingerprintSha256 = "fp-$id",
    )

    @Test
    fun `splits on the mint label preserving each side's order`() {
        val (user, drive) = partitionDriveKeys(
            listOf(
                key("d1", SshKey.USB_DRIVE_VM_LABEL),
                key("u1", "laptop"),
                key("d2", SshKey.USB_DRIVE_VM_LABEL),
                key("u2", "yubikey"),
            ),
        )
        assertEquals(listOf("u1", "u2"), user.map { it.id })
        assertEquals(listOf("d1", "d2"), drive.map { it.id })
    }

    @Test
    fun `a renamed drive key is a user key`() {
        val (user, drive) = partitionDriveKeys(listOf(key("d1", "my drive key")))
        assertEquals(listOf("d1"), user.map { it.id })
        assertEquals(emptyList<SshKey>(), drive)
    }
}
