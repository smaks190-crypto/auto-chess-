package dev.nox.local

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** Events are hints only: no text, node handles, or screen content is retained. */
internal class UiChangeRelay {
    private val active = AtomicReference<UiChangeSignal?>(null)
    fun begin(pkg: String, window: Int): UiChangeSignal? {
        val signal = UiChangeSignal(pkg, window)
        return if (active.compareAndSet(null, signal)) signal else null
    }
    fun publish(pkg: String?, window: Int) { active.get()?.publish(pkg, window) }
    fun end(signal: UiChangeSignal) {
        signal.close()
        active.compareAndSet(signal, null)
    }
}

internal class UiChangeSignal(private val pkg: String, private val window: Int) {
    private val lock = java.lang.Object()
    private var sequence = 0L
    private var closed = false
    fun mark(): Long = synchronized(lock) { sequence }
    fun publish(pkg: String?, window: Int) {
        if (this.pkg != pkg || this.window != window) return
        synchronized(lock) {
            if (!closed) { sequence++; lock.notifyAll() }
        }
    }
    fun close() { synchronized(lock) { closed = true; lock.notifyAll() } }

    /** Worker-only wait; a pre-arrived event is not lost. Cancellation is checked outside the lock. */
    fun awaitAfter(marker: Long, timeoutMs: Long, checkActive: () -> Unit): Long {
        val start = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (true) {
            checkActive()
            synchronized(lock) {
                val left = budget - (System.nanoTime() - start)
                if (closed || sequence != marker || left <= 0L) return sequence
                val millis = TimeUnit.NANOSECONDS.toMillis(left).coerceIn(1L, 40L)
                lock.wait(millis)
            }
        }
    }
}
