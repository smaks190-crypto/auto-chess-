package dev.nox.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object BoundedHandoffChecks {
    private val before = PlanningState("t","p",1,1,"old")
    private val after = before.copy(sequence=2,signature="new")
    private fun now() = System.nanoTime()/1_000_000
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
        test("waits for matching in-flight plan") {
            SpeculativePlanner<String>(::now,{false}).use { p ->
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                check(p.offer(before,after,now()+2000) { started.countDown(); release.await(); "intent" })
                check(started.await(1,TimeUnit.SECONDS))
                val t = thread { Thread.sleep(50); release.countDown() }
                check(p.take(after,true,500)=="intent"); t.join()
                check(p.take(after,true,500)==null)
            }
        }
        test("wrong state never waits or consumes result") {
            SpeculativePlanner<String>(::now,{false}).use { p ->
                val entered=CountDownLatch(1); val cancelled=CountDownLatch(1)
                check(p.offer(before,after,now()+2000) { entered.countDown(); try { CountDownLatch(1).await(); "bad" } finally { cancelled.countDown() } })
                check(entered.await(1,TimeUnit.SECONDS))
                check(p.take(after.copy(signature="different"),true,500)==null)
                check(cancelled.await(1,TimeUnit.SECONDS))
            }
        }
        test("timeout cancels in-flight computation") {
            SpeculativePlanner<String>(::now,{false}).use { p ->
                val entered=CountDownLatch(1); val ended=CountDownLatch(1)
                check(p.offer(before,after,now()+2000) { entered.countDown(); try { CountDownLatch(1).await(); "late" } finally { ended.countDown() } })
                check(entered.await(1,TimeUnit.SECONDS))
                check(p.take(after,true,35)==null)
                check(ended.await(1,TimeUnit.SECONDS)); check(p.take(after,true)==null)
            }
        }
        test("cancellation during wait is observed") {
            val stop=AtomicBoolean(false)
            SpeculativePlanner<String>(::now,stop::get).use { p ->
                val entered=CountDownLatch(1); val ended=CountDownLatch(1)
                check(p.offer(before,after,now()+2000) { entered.countDown(); try { CountDownLatch(1).await(); "bad" } finally { ended.countDown() } })
                check(entered.await(1,TimeUnit.SECONDS))
                val t=thread { Thread.sleep(50); stop.set(true) }
                check(p.take(after,true,500)==null); t.join(); check(ended.await(1,TimeUnit.SECONDS))
            }
        }
        test("deadline expires while waiting") {
            val clock=AtomicLong(0)
            SpeculativePlanner<String>(clock::get,{false}).use { p ->
                val entered=CountDownLatch(1); val ended=CountDownLatch(1)
                check(p.offer(before,after,100) { entered.countDown(); try { CountDownLatch(1).await(); "bad" } finally { ended.countDown() } })
                check(entered.await(1,TimeUnit.SECONDS))
                val t=thread { Thread.sleep(50); clock.set(100) }
                check(p.take(after,true,500)==null); t.join(); check(ended.await(1,TimeUnit.SECONDS))
            }
        }
        test("unverified previous action rejects even a prepared plan") {
            SpeculativePlanner<String>(::now,{false}).use { p ->
                check(p.offer(before,after,now()+2000) { "bad" })
                check(p.take(after,false,500)==null)
            }
        }
        println("$count/$count handoff checks passed")
    }
}
