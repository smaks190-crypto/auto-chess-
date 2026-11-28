package dev.nox.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object SpeculativePlannerChecks {
    private val before = PlanningState("task", "pkg", 1, 10, "before")
    private val predicted = before.copy(sequence = 11, signature = "after")
    private fun readyCase(actual: PlanningState = predicted, verified: Boolean = true,
                          expired: Boolean = false, cancelled: Boolean = false,
                          failure: Boolean = false, expect: String? = null) {
        var time = 10L
        var stop = false
        SpeculativePlanner<String>({ time }, { stop }).use { planner ->
            val done = CountDownLatch(1)
            check(planner.offer(before, predicted, 100) {
                try { if (failure) error("failed"); "next intent" } finally { done.countDown() }
            })
            check(done.await(2, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!planner.isReady() && System.nanoTime() < until) Thread.yield()
            check(planner.isReady())
            if (expired) time = 100
            stop = cancelled
            check(planner.take(actual, verified) == expect)
            check(planner.take(actual, verified) == null)
        }
    }
    fun success() = readyCase(actual = predicted.copy(sequence = 12), expect = "next intent")
    fun divergent() = readyCase(actual = predicted.copy(signature = "different"))
    fun oldFrame() = readyCase(actual = predicted.copy(sequence = 10))
    fun wrongPackage() = readyCase(actual = predicted.copy(pkg = "other"))
    fun wrongTask() = readyCase(actual = predicted.copy(taskId = "other"))
    fun wrongEpoch() = readyCase(actual = predicted.copy(epoch = 2))
    fun unverified() = readyCase(verified = false)
    fun expired() = readyCase(expired = true)
    fun cancelled() = readyCase(cancelled = true)
    fun failed() = readyCase(failure = true)
    fun notReady() {
        SpeculativePlanner<String>({ 10L }, { false }).use { planner ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            check(planner.offer(before, predicted, 100) { started.countDown(); release.await(); "late" })
            check(started.await(2, TimeUnit.SECONDS))
            check(planner.take(predicted, true) == null)
            release.countDown()
            check(planner.take(predicted, true) == null)
        }
    }
    fun invalidOffer() {
        SpeculativePlanner<String>({ 10L }, { false }).use { planner ->
            check(!planner.offer(before, predicted.copy(epoch = 2), 100) { error("must not run") })
            check(!planner.offer(before, predicted.copy(sequence = 10), 100) { error("must not run") })
            check(!planner.offer(before, predicted, 10) { error("must not run") })
        }
    }
    fun busyWorker() {
        SpeculativePlanner<String>({ 10L }, { false }).use { planner ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                check(planner.offer(before, predicted, 100) {
                    started.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    "late"
                })
                check(started.await(2, TimeUnit.SECONDS))
                check(!planner.offer(before, predicted, 100) { error("concurrent worker") })
                check(planner.take(predicted, true) == null)
            } finally { release.countDown() }
        }
    }
    fun closed() {
        val planner = SpeculativePlanner<String>({ 10L }, { false })
        planner.close()
        check(!planner.offer(before, predicted, 100) { error("closed") })
    }
    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(::success, ::divergent, ::oldFrame, ::wrongPackage, ::wrongTask, ::wrongEpoch,
            ::unverified, ::expired, ::cancelled, ::failed, ::notReady, ::invalidOffer, ::busyWorker, ::closed)
        cases.forEach { it(); println("PASS ${it.name}") }
        println("${cases.size}/${cases.size} passed")
    }
}
