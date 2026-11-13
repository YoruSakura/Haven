package sh.haven.core.ssh.openkeychain

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.openintents.ssh.authentication.ISshAuthenticationService
import org.openintents.ssh.authentication.SshAuthenticationApiError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "OpenKeychainClient"

/** How long to wait for the provider's service to bind. */
private const val BIND_TIMEOUT_SECONDS = 10L

/**
 * How long to wait for the user to deal with the provider's prompt — a
 * passphrase, or a PIN and a tap on a hardware token. Deliberately generous:
 * finding the token and holding it to the phone is not a fast operation, and
 * the cost of being too short is an authentication that fails after the user
 * did everything right.
 */
private const val USER_INTERACTION_TIMEOUT_SECONDS = 180L

/**
 * How many consecutive prompts a provider may ask for before Haven treats it
 * as stuck. OpenKeychain's longest legitimate run is three — app permission,
 * key chooser, then PIN and tap for an on-card key — so four leaves a round of
 * headroom without letting a genuinely looping provider hold the caller.
 */
private const val MAX_USER_INTERACTION_ROUNDS = 4

/** The provider refused the request; [error] is its code, if it sent one. */
class OpenKeychainException(
    message: String,
    val error: Int? = null,
) : Exception(message)

/**
 * Plain-language name for an [SshAuthenticationApiError] code (#487). Falls
 * back to the number rather than hiding it — an unrecognised code is still
 * something a reporter can quote.
 */
internal fun describeError(code: Int): String = when (code) {
    SshAuthenticationApiError.CLIENT_SIDE_ERROR -> "the request was malformed (client-side error)"
    SshAuthenticationApiError.GENERIC_ERROR -> "no reason given (generic error)"
    SshAuthenticationApiError.INCOMPATIBLE_API_VERSIONS ->
        "the provider speaks a different version of the SSH Authentication API"
    SshAuthenticationApiError.INTERNAL_ERROR -> "the provider hit an internal error"
    SshAuthenticationApiError.UNKNOWN_ACTION -> "the provider does not support this operation"
    SshAuthenticationApiError.NO_KEY_ID -> "no key id was sent with the request"
    SshAuthenticationApiError.NO_SUCH_KEY -> "the provider has no key with that id"
    SshAuthenticationApiError.NO_AUTH_KEY ->
        "that key has no authentication subkey the provider can use for SSH"
    SshAuthenticationApiError.INVALID_ALGORITHM -> "the provider rejected the key algorithm"
    SshAuthenticationApiError.INVALID_HASH_ALGORITHM -> "the provider rejected the hash algorithm"
    else -> "provider error code $code"
}

/**
 * Talks to an SSH Authentication API provider (#487).
 *
 * Every call blocks, because the only caller that matters is JSch's
 * authentication, which is synchronous — the same reason
 * `sh.haven.core.fido.FidoIdentity` blocks on the hardware token.
 *
 * A request can come back asking for user interaction (unlock the key, enter
 * the PIN, tap the token). That is not a failure: the provider hands over a
 * [PendingIntent], and once the user has finished with it the original
 * request is re-sent and succeeds. [OpenKeychainPromptActivity] runs that
 * step, since a signature requested from a background thread has no activity
 * of its own to launch from.
 */
class OpenKeychainClient(
    context: Context,
    private val providerPackage: String,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var service: ISshAuthenticationService? = null
    private var connection: ServiceConnection? = null

    /** Test seam: hand a request to the bound provider service. */
    internal var callService: (Intent) -> Intent = { bind().execute(it) }

    /**
     * Test seam: run one provider prompt. Returns what the prompt handed back
     * — the request to send next — or null if the user backed out.
     */
    internal var runPrompt: (PendingIntent) -> Intent? = { OpenKeychainPromptActivity.await(appContext, it) }

    /**
     * Bind, blocking until the service arrives or [BIND_TIMEOUT_SECONDS]
     * passes. Idempotent — a live binding is reused.
     */
    @Synchronized
    private fun bind(): ISshAuthenticationService {
        service?.let { return it }

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = ISshAuthenticationService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        val intent = Intent(OpenKeychainApi.SERVICE_INTENT).setPackage(providerPackage)
        val started = try {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            throw OpenKeychainException("Not allowed to reach $providerPackage: ${e.message}")
        }
        if (!started) {
            throw OpenKeychainException(
                "$providerPackage did not accept the connection — is it still installed?",
            )
        }
        connection = conn

        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw OpenKeychainException("$providerPackage did not respond within ${BIND_TIMEOUT_SECONDS}s")
        }
        return service ?: throw OpenKeychainException("$providerPackage connected without a service")
    }

    /** Release the binding. Safe to call when never bound. */
    @Synchronized
    fun close() {
        connection?.let {
            runCatching { appContext.unbindService(it) }
                .onFailure { e -> Log.w(TAG, "unbind failed: ${e.message}") }
        }
        connection = null
        service = null
    }

    /**
     * Send [request], resolving up to [MAX_USER_INTERACTION_ROUNDS] rounds of
     * user interaction.
     *
     * ★ #487: this used to resolve exactly one round, on the reasoning that a
     * provider asking again after the user had satisfied a prompt was not
     * making progress. That reasoning was wrong. Consecutive prompts are the
     * normal path, and they are *different* prompts: OpenKeychain asks first
     * for permission to talk to this app, then shows its key chooser, then —
     * for an on-card key — asks for the PIN and a tap. Each is one round.
     *
     * Stopping after one turned the second prompt into a failure, and a
     * silent-looking one: a `USER_INTERACTION_REQUIRED` reply carries a
     * PendingIntent and no error extra, so it fell through to [errorFrom],
     * which found nothing to report and produced the bare "the key provider
     * refused the request" with no code behind it. A reporter whose app
     * permission was already granted saw exactly that — the permission round
     * resolved with nothing on screen, and the chooser round was rejected as
     * a refusal before it could be shown.
     *
     * Still bounded, for the original reason: a provider that really is stuck
     * re-prompting must not trap the caller forever. The bound is a count of
     * rounds rather than a "same prompt twice" check, because two rounds
     * legitimately carry equal PendingIntents when the provider reuses one.
     *
     * ★★ The other half of the same bug, and the one that actually made the
     * chooser unreachable: **the request to send next is the one the prompt
     * handed back, not the one we sent.** OpenKeychain attaches our request to
     * the prompt as `EXTRA_DATA` and its key chooser returns it augmented —
     * `originalIntent.putExtra(EXTRA_KEY_ID, …); setResult(RESULT_OK,
     * originalIntent)`, verbatim from `RemoteSelectAuthenticationKeyActivity`.
     * Re-sending our own copy throws the answer away, so the provider finds no
     * key id and redirects to key selection again — forever, had the round
     * limit not stopped it first. Every prompt works this way: the passphrase
     * and security-token dialogs return a `CryptoInputParcel` by the same
     * route.
     */
    internal fun execute(request: Intent): Intent {
        var current = request
        current.putExtra(OpenKeychainApi.EXTRA_API_VERSION, OpenKeychainApi.API_VERSION)
        var result = callService(current)
        var rounds = 0
        while (true) {
            when (result.getIntExtra(OpenKeychainApi.EXTRA_RESULT_CODE, OpenKeychainApi.RESULT_CODE_ERROR)) {
                OpenKeychainApi.RESULT_CODE_SUCCESS -> return result
                OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                    if (++rounds > MAX_USER_INTERACTION_ROUNDS) {
                        throw OpenKeychainException(
                            "$providerPackage asked for user interaction $MAX_USER_INTERACTION_ROUNDS " +
                                "times without completing the request",
                        )
                    }
                    val pending = pendingIntentOf(result)
                        ?: throw OpenKeychainException("Provider asked for user interaction but sent no prompt")
                    // Each round is a distinct prompt (permission, chooser, PIN
                    // and tap), and which one a stuck handshake died on is the
                    // difference between three unrelated causes — so say which
                    // round this is, and whether the user answered it (#487).
                    Log.d(TAG, "prompt round $rounds of $MAX_USER_INTERACTION_ROUNDS")
                    val answered = runPrompt(pending)
                    if (answered == null) {
                        Log.w(TAG, "prompt round $rounds returned no result — treating as cancelled")
                        throw OpenKeychainException("Cancelled")
                    }
                    // Fall back to what we sent only if the prompt returned
                    // nothing at all — better a repeat than an empty request.
                    if (answered.extras?.isEmpty == false) current = answered
                    current.putExtra(OpenKeychainApi.EXTRA_API_VERSION, OpenKeychainApi.API_VERSION)
                    result = callService(current)
                }
                else -> throw errorFrom(result)
            }
        }
    }

    /**
     * The public key for [keyId], in OpenSSH form.
     *
     * Fetched when the key is first chosen, and stored, so that connecting
     * does not depend on the provider being reachable until there is an
     * actual signature to make.
     */
    fun fetchPublicKey(keyId: String): OpenKeychainPublicKey {
        val result = execute(
            Intent(OpenKeychainApi.ACTION_GET_SSH_PUBLIC_KEY)
                .putExtra(OpenKeychainApi.EXTRA_KEY_ID, keyId),
        )
        val line = result.getStringExtra(OpenKeychainApi.EXTRA_SSH_PUBLIC_KEY)
            ?: throw OpenKeychainException("Provider returned no public key for $keyId")
        return OpenKeychainApi.parsePublicKey(line)
            ?: throw OpenKeychainException("Provider returned an unreadable public key: $line")
    }

    /**
     * Sign [challenge] as [sshAlgorithm], returning the SSH signature blob.
     *
     * The provider encodes the blob itself — algorithm name and signature,
     * per RFC 4253 — so the result goes to JSch untouched. For RSA the hash
     * also picks the algorithm name, which is why it is derived from
     * [sshAlgorithm] rather than fixed.
     */
    fun sign(keyId: String, challenge: ByteArray, sshAlgorithm: String): ByteArray {
        val hash = OpenKeychainApi.hashAlgorithmFor(sshAlgorithm)
            ?: throw OpenKeychainException("$sshAlgorithm is not an algorithm this provider can sign with")
        val result = execute(
            Intent(OpenKeychainApi.ACTION_SIGN)
                .putExtra(OpenKeychainApi.EXTRA_KEY_ID, keyId)
                .putExtra(OpenKeychainApi.EXTRA_CHALLENGE, challenge)
                .putExtra(OpenKeychainApi.EXTRA_HASH_ALGORITHM, hash),
        )
        return result.getByteArrayExtra(OpenKeychainApi.EXTRA_SIGNATURE)
            ?: throw OpenKeychainException("Provider returned no signature")
    }

    /**
     * Show the provider's key chooser and return what the user picked, or
     * null if they backed out.
     *
     * The provider drives this entirely through the user-interaction path:
     * the first response is always the chooser prompt, and the id only comes
     * back on the retry.
     */
    fun selectKey(): OpenKeychainSelection? = try {
        val result = execute(Intent(OpenKeychainApi.ACTION_SELECT_KEY))
        val keyId = result.getStringExtra(OpenKeychainApi.EXTRA_KEY_ID)
        if (keyId == null) {
            null
        } else {
            OpenKeychainSelection(
                keyId = keyId,
                description = result.getStringExtra(OpenKeychainApi.EXTRA_KEY_DESCRIPTION).orEmpty(),
            )
        }
    } catch (e: OpenKeychainException) {
        if (e.message == "Cancelled") null else throw e
    }

    private fun errorFrom(result: Intent): OpenKeychainException {
        val error = errorExtra(result)
        val detail = error?.message?.takeIf { it.isNotBlank() }
        val code = error?.error
        // #487: a bare "refused the request" is unactionable, and the reporter
        // who got one had no way to tell it apart from a bug in Haven. The
        // provider sends a code even when it sends no text, so say which.
        val fallback = code?.let { "The key provider refused the request: ${describeError(it)}" }
            ?: "The key provider refused the request"
        return OpenKeychainException(detail ?: fallback, code)
    }

    @Suppress("DEPRECATION")
    private fun errorExtra(result: Intent): SshAuthenticationApiError? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_ERROR, SshAuthenticationApiError::class.java)
        } else {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_ERROR)
        }
    } catch (e: RuntimeException) {
        // A malformed parcel must not mask the failure it describes.
        Log.w(TAG, "unreadable error extra: ${e.message}")
        null
    }

    @Suppress("DEPRECATION")
    private fun pendingIntentOf(result: Intent): PendingIntent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_PENDING_INTENT, PendingIntent::class.java)
        } else {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_PENDING_INTENT)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "unreadable pending intent: ${e.message}")
        null
    }
}

/** A key the user picked from the provider's chooser. */
data class OpenKeychainSelection(val keyId: String, val description: String)

/**
 * Runs a provider's [PendingIntent] and reports whether the user completed it.
 *
 * A signature is requested from JSch's thread, which has no activity to
 * launch a prompt from and may be running while Haven is in the background.
 * This activity is that launcher: transparent, started in its own task, alive
 * only for the round trip.
 */
class OpenKeychainPromptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        if (savedInstanceState != null) return // already launched; waiting on the result

        @Suppress("DEPRECATION")
        val pending: PendingIntent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PENDING, PendingIntent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_PENDING)
            }
        if (pending == null) {
            finishWith(null)
            return
        }
        try {
            startIntentSenderForResult(pending.intentSender, REQUEST_CODE, null, 0, 0, 0)
        } catch (e: android.content.IntentSender.SendIntentException) {
            Log.w(TAG, "prompt could not be shown: ${e.message}")
            finishWith(null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // ★ #487: `data` is the answer, not a formality. OpenKeychain returns
        // the request we sent with what it learned added — the chosen key id,
        // a CryptoInputParcel — and the next call has to carry that or the
        // provider asks the same question again. This used to keep only
        // `resultCode == RESULT_OK` and drop the rest on the floor.
        if (requestCode == REQUEST_CODE) {
            finishWith(if (resultCode == RESULT_OK) data ?: Intent() else null)
        }
    }

    private fun finishWith(answer: Intent?) {
        waiters.remove(token)?.complete(Optional(answer))
        finish()
    }

    private var token: Long = -1L

    companion object {
        private const val REQUEST_CODE = 0x0C4A
        private const val EXTRA_PENDING = "sh.haven.openkeychain.PENDING"
        private const val EXTRA_TOKEN = "sh.haven.openkeychain.TOKEN"

        private val nextToken = AtomicLong(1)

        /** Boxed so a "finished, with nothing" answer is not a missing one. */
        private class Optional(val value: Intent?)

        private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Optional>>()

        /**
         * Show [pending] and block until the user is done with it.
         *
         * Returns the intent the prompt handed back — which is the request to
         * send next — or null if they cancelled, if it timed out, or if the
         * prompt could not be shown at all. Those all mean the same thing to
         * the caller: no answer, so nothing to retry with.
         */
        fun await(context: Context, pending: PendingIntent): Intent? {
            val token = nextToken.getAndIncrement()
            val deferred = CompletableDeferred<Optional>()
            waiters[token] = deferred

            val launch = Intent(context, OpenKeychainPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PENDING, pending)
                .putExtra(EXTRA_TOKEN, token)
            context.startActivity(launch)

            return try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(
                        USER_INTERACTION_TIMEOUT_SECONDS * 1000,
                    ) { deferred.await() }?.value
                }
            } finally {
                waiters.remove(token)
            }
        }
    }
}
