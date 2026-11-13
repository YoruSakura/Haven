package sh.haven.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #461: Windows 11 accepted the credentials and then showed the lock screen,
 * because a `MicrosoftAccount\you@example.com` typed into the username box was
 * sent whole as the username with an empty domain. Splitting it is the fix; the
 * tests that matter most here are the ones pinning what must NOT be split.
 */
class RdpUsernameTest {

    @Test
    fun `a Microsoft account qualification is split into domain and user`() {
        assertEquals(
            RdpLogonName("you@example.com", "MicrosoftAccount"),
            qualifyRdpLogon("MicrosoftAccount\\you@example.com", ""),
        )
    }

    @Test
    fun `a domain-qualified name is split`() {
        assertEquals(RdpLogonName("alice", "CORP"), qualifyRdpLogon("CORP\\alice", ""))
    }

    /** `.\user` means "the local machine" and is a domain like any other here. */
    @Test
    fun `the local-machine prefix is split`() {
        assertEquals(RdpLogonName("alice", "."), qualifyRdpLogon(".\\alice", ""))
    }

    /**
     * The one that stops this fix causing a worse bug: a bare UPN is a valid
     * Active Directory logon and must be passed through. Guessing
     * `MicrosoftAccount` here would break every AD user to help a Microsoft
     * account we cannot identify from the string.
     */
    @Test
    fun `a bare UPN is never qualified`() {
        assertEquals(RdpLogonName("alice@corp.example.com", ""), qualifyRdpLogon("alice@corp.example.com", ""))
    }

    @Test
    fun `a plain username is left alone`() {
        assertEquals(RdpLogonName("alice", ""), qualifyRdpLogon("alice", ""))
    }

    /** An explicit domain is a deliberate choice; never override or discard it. */
    @Test
    fun `an explicit domain field wins over a qualified username`() {
        assertEquals(
            RdpLogonName("CORP\\alice", "OTHER"),
            qualifyRdpLogon("CORP\\alice", "OTHER"),
        )
    }

    @Test
    fun `an explicit domain is kept for an unqualified username`() {
        assertEquals(RdpLogonName("alice", "CORP"), qualifyRdpLogon("alice", "CORP"))
    }

    /** Half-typed entries must not turn into an empty username. */
    @Test
    fun `a trailing separator is not a qualification`() {
        assertEquals(RdpLogonName("CORP\\", ""), qualifyRdpLogon("CORP\\", ""))
    }

    /**
     * FreeRDP drops a leading separator and leaves the domain empty, which
     * Windows accepts; passing `\alice` through as the username would not work.
     */
    @Test
    fun `a leading separator is dropped, leaving an empty domain`() {
        assertEquals(RdpLogonName("alice", ""), qualifyRdpLogon("\\alice", ""))
    }

    /** Only the first separator splits — a password-manager paste may add more. */
    @Test
    fun `only the first separator splits`() {
        assertEquals(RdpLogonName("weird\\name", "CORP"), qualifyRdpLogon("CORP\\weird\\name", ""))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            RdpLogonName("you@example.com", "MicrosoftAccount"),
            qualifyRdpLogon("  MicrosoftAccount\\you@example.com  ", "  "),
        )
    }

    @Test
    fun `an empty username stays empty`() {
        assertEquals(RdpLogonName("", ""), qualifyRdpLogon("", ""))
    }
}
