package sh.haven.core.usb

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val TAG = "UsbAccessGate"

/** Tracks USB device leases during FIDO2 operations. */
@Singleton
class UsbAccessGate @Inject constructor() {

    private val lock = ReentrantLock()
    private val cleared = lock.newCondition()
    /** Lease counts by device name; >0 ⇒ a priority consumer holds the device. Guarded by [lock]. */
    private val holders = HashMap<String, Int>()

    /** Take an exclusive lease on [deviceName]. Re-entrant; balance with [release]. */
    fun acquire(deviceName: String) = lock.withLock {
        holders[deviceName] = (holders[deviceName] ?: 0) + 1
    }

    /** Release one lease on [deviceName] and wake waiting consumers. */
    fun release(deviceName: String) = lock.withLock {
        val n = (holders[deviceName] ?: 0) - 1
        if (n <= 0) holders.remove(deviceName) else holders[deviceName] = n
        cleared.signalAll()
    }

    fun isHeld(deviceName: String): Boolean = lock.withLock { (holders[deviceName] ?: 0) > 0 }

    /**
     * Block until no lease is held on [deviceName], up to [timeoutMs]. Returns
     * true if the device is clear, false on timeout — the caller then proceeds
     * anyway, since a brief contended transfer is better than wedging the export
     * if a lease is never released (e.g. the holder's thread died).
     */
    fun awaitClear(deviceName: String, timeoutMs: Long): Boolean = lock.withLock {
        if ((holders[deviceName] ?: 0) == 0) return@withLock true
        var remaining = timeoutMs * 1_000_000 // nanos
        while ((holders[deviceName] ?: 0) > 0) {
            if (remaining <= 0) {
                Log.w(TAG, "USB device $deviceName still has a FIDO lease (waited ${timeoutMs}ms)")
                return@withLock false
            }
            remaining = cleared.awaitNanos(remaining)
        }
        true
    }
}
