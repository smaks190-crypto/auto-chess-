package dev.nox.local

import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A semantic snapshot, not an accessibility node ID or cached screen coordinate. */
internal data class PlanningState(
    val taskId: String, val pkg: String, val epoch: Long,
    val sequence: Long, val signature: String
) {
    init { require(taskId.isNotBlank() && pkg.isNotBlank() && epoch >= 0 && sequence >= 0 && signature.isNotBlank()) }
}

/** At most one read-only speculative job. The caller must resolve its intent on a fresh
 * observation and use the existing executor/approval gate; this class never dispatches input.
 * All public methods belong to the task owner; the worker only sees an immutable snapshot.
 */
internal class SpeculativePlanner<I : Any>(
    private val now: () -> Long,
    private val cancelled: () -> Boolean
) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(), { r -> Thread(r, "nox-speculative-planner").apply { isDaemon = true } })
    private data class Pending<I>(val predicted: PlanningState,
        val deadline: Long, val future: FutureTask<I>)
    private var pending: Pending<I>? = null
    private var closed = false

    fun offer(before: PlanningState, predicted: PlanningState, deadline: Long,
              plan: (PlanningState) -> I): Boolean {
        discard()
        if (closed || cancelled() || now() >= deadline || !sameScope(before, predicted) ||
            predicted.sequence <= before.sequence) return false
        val future = FutureTask { plan(predicted) }
        try { worker.execute(future) } catch (_: RejectedExecutionException) { return false }
        pending = Pending(predicted, deadline, future)
        return true
    }

    /** One-shot handoff. Wait only for a matching, independently verified prediction.
     * Zero budget preserves non-blocking behavior; polling bounds cancellation latency.
     * A timeout cancels the read-only job, never retries a device mutation. */
    fun take(actual: PlanningState, verifiedPrevious: Boolean, waitMs: Long = 0): I? {
        require(waitMs in 0..1000)
        val p = pending ?: return null
        pending = null
        fun valid() = !closed && !cancelled() && verifiedPrevious && now() < p.deadline &&
            sameScope(p.predicted, actual) && actual.sequence >= p.predicted.sequence &&
            actual.signature == p.predicted.signature && !p.future.isCancelled
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(waitMs)
        try {
            if (!valid()) return null
            while (!p.future.isDone) {
                if (!valid()) return null
                val left = budget - (System.nanoTime() - started)
                val deadlineLeft = p.deadline - now()
                val slice = minOf(left, TimeUnit.MILLISECONDS.toNanos(minOf(25L, deadlineLeft)))
                if (slice <= 0) return null
                try { return p.future.get(slice, TimeUnit.NANOSECONDS).takeIf { valid() } }
                catch (_: TimeoutException) { /* Only wait for this read-only job. */ }
            }
            return p.future.get().takeIf { valid() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return null
        } catch (_: Exception) {
            return null
        } finally {
            if (!p.future.isDone) p.future.cancel(true)
        }
    }

    fun isReady(): Boolean = pending?.future?.isDone == true

    fun discard() { pending?.future?.cancel(true); pending = null }
    override fun close() { if (!closed) { closed = true; discard(); worker.shutdownNow() } }

    private fun sameScope(a: PlanningState, b: PlanningState): Boolean =
        a.taskId == b.taskId && a.pkg == b.pkg && a.epoch == b.epoch
}
