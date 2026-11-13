package sh.haven.core.ssh.openkeychain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #487: a reporter could not import a key from OpenKeychain — "The key provider
 * refused the request", about 2.5s after the prompt opened, with no card
 * interaction and no error code.
 *
 * The client resolved exactly one round of user interaction. OpenKeychain's
 * real flow is several: app permission, then the key chooser, then the PIN for
 * an on-card key. A `USER_INTERACTION_REQUIRED` reply carries a PendingIntent
 * and *no* error extra, so the second one was read as a refusal that had
 * nothing to say for itself — which is precisely the message with no code.
 */
class OpenKeychainClientRoundsTest {

    private fun client(): OpenKeychainClient {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return OpenKeychainClient(context, "org.sufficientlysecure.keychain")
    }

    /**
     * What a prompt hands back: OpenKeychain returns the request we sent with
     * what it learned added, so a non-empty extras bundle is the signal that
     * there is something to carry forward.
     */
    private fun answer(): Intent {
        val intent = mockk<Intent>(relaxed = true)
        val extras = mockk<android.os.Bundle>(relaxed = true)
        every { extras.isEmpty } returns false
        every { intent.extras } returns extras
        return intent
    }

    /** A provider reply carrying [code], and a prompt when it needs one. */
    private fun reply(code: Int, pending: PendingIntent? = null): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getIntExtra(OpenKeychainApi.EXTRA_RESULT_CODE, any()) } returns code
        every {
            intent.getParcelableExtra<PendingIntent>(OpenKeychainApi.EXTRA_PENDING_INTENT)
        } returns pending
        every {
            intent.getParcelableExtra(OpenKeychainApi.EXTRA_PENDING_INTENT, PendingIntent::class.java)
        } returns pending
        // A USER_INTERACTION_REQUIRED reply has no error extra. That is the
        // whole reason the old code produced a message with nothing in it.
        every { intent.getParcelableExtra<android.os.Parcelable>(OpenKeychainApi.EXTRA_ERROR) } returns null
        return intent
    }

    /**
     * The reporter's case: permission already granted, so round one resolves
     * with nothing on screen, and round two is the chooser. Fails before the
     * fix with "The key provider refused the request".
     */
    @Test
    fun `two consecutive prompts complete rather than reading as a refusal`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val success = reply(OpenKeychainApi.RESULT_CODE_SUCCESS)
        val replies = ArrayDeque(
            listOf(
                reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt),
                reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt),
                success,
            ),
        )
        var promptsShown = 0

        val client = client()
        client.callService = { replies.removeFirst() }
        client.runPrompt = { promptsShown++; answer() }

        assertEquals(success, client.execute(mockk(relaxed = true)))
        assertEquals("both prompts should be shown to the user", 2, promptsShown)
    }

    /** Three rounds is OpenKeychain's longest real flow: permission, chooser, PIN. */
    @Test
    fun `three consecutive prompts still complete`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val success = reply(OpenKeychainApi.RESULT_CODE_SUCCESS)
        val replies = ArrayDeque(
            List(3) { reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt) } + success,
        )

        val client = client()
        client.callService = { replies.removeFirst() }
        client.runPrompt = { answer() }

        assertEquals(success, client.execute(mockk(relaxed = true)))
    }

    /** The bound is still real — a provider that only ever re-prompts is stopped. */
    @Test
    fun `a provider that never stops prompting is given up on, and says so`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val client = client()
        client.callService = { reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt) }
        client.runPrompt = { answer() }

        val e = assertThrows(OpenKeychainException::class.java) { client.execute(mockk(relaxed = true)) }
        assertTrue(
            "should name the looping provider, got: ${e.message}",
            e.message!!.contains("without completing the request"),
        )
    }

    /** Backing out of any round is a cancel, not an error. */
    @Test
    fun `cancelling the second prompt cancels rather than reporting a refusal`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val replies = ArrayDeque(
            List(2) { reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt) },
        )
        var shown = 0

        val client = client()
        client.callService = { replies.removeFirst() }
        client.runPrompt = { if (++shown < 2) answer() else null }

        val e = assertThrows(OpenKeychainException::class.java) { client.execute(mockk(relaxed = true)) }
        assertEquals("Cancelled", e.message)
    }

    /**
     * ★ The half that actually made the chooser unreachable. OpenKeychain's
     * key chooser returns *our request with the chosen key id added*
     * (`RemoteSelectAuthenticationKeyActivity`: `originalIntent.putExtra(
     * EXTRA_KEY_ID, …); setResult(RESULT_OK, originalIntent)`). Re-sending our
     * own copy throws that away, and the provider redirects to key selection
     * again — which is why a bounded loop alone would just show the chooser
     * four times and then give up.
     */
    @Test
    fun `the request sent after a prompt is the one the prompt handed back`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val answered = answer()
        val sent = mutableListOf<Intent>()
        val replies = ArrayDeque(
            listOf(
                reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt),
                reply(OpenKeychainApi.RESULT_CODE_SUCCESS),
            ),
        )

        val client = client()
        client.callService = { sent += it; replies.removeFirst() }
        client.runPrompt = { answered }

        client.execute(mockk(relaxed = true))

        assertEquals("two requests should have gone to the provider", 2, sent.size)
        assertEquals(
            "the second request must be the intent the prompt returned, not the original",
            answered,
            sent[1],
        )
    }

    /** A prompt that hands back nothing falls back to the request we sent. */
    @Test
    fun `an empty prompt answer does not blank out the request`() {
        val prompt = mockk<PendingIntent>(relaxed = true)
        val empty = mockk<Intent>(relaxed = true)
        every { empty.extras } returns null
        val sent = mutableListOf<Intent>()
        val replies = ArrayDeque(
            listOf(
                reply(OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED, prompt),
                reply(OpenKeychainApi.RESULT_CODE_SUCCESS),
            ),
        )

        val client = client()
        client.callService = { sent += it; replies.removeFirst() }
        client.runPrompt = { empty }

        client.execute(mockk(relaxed = true))

        assertEquals(2, sent.size)
        assertEquals("should re-send the original, not the empty answer", sent[0], sent[1])
    }

    /** A genuine error still surfaces as one, unchanged. */
    @Test
    fun `an error reply is still an error`() {
        val client = client()
        client.callService = { reply(OpenKeychainApi.RESULT_CODE_ERROR) }
        client.runPrompt = { answer() }

        assertThrows(OpenKeychainException::class.java) { client.execute(mockk(relaxed = true)) }
    }
}

/** #487: a refusal with a code must say what the code means, not just "refused". */
class OpenKeychainErrorTextTest {

    @Test
    fun `a known code is named in plain language`() {
        assertTrue(
            describeError(org.openintents.ssh.authentication.SshAuthenticationApiError.NO_AUTH_KEY)
                .contains("authentication subkey"),
        )
    }

    @Test
    fun `an unknown code still surfaces its number rather than vanishing`() {
        assertEquals("provider error code -9999", describeError(-9999))
    }
}
