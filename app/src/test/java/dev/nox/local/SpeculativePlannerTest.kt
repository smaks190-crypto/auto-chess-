package dev.nox.local

import org.junit.Test

class SpeculativePlannerTest {
    @Test fun success() = SpeculativePlannerChecks.success()
    @Test fun divergent() = SpeculativePlannerChecks.divergent()
    @Test fun oldFrame() = SpeculativePlannerChecks.oldFrame()
    @Test fun wrongPackage() = SpeculativePlannerChecks.wrongPackage()
    @Test fun wrongTask() = SpeculativePlannerChecks.wrongTask()
    @Test fun wrongEpoch() = SpeculativePlannerChecks.wrongEpoch()
    @Test fun unverified() = SpeculativePlannerChecks.unverified()
    @Test fun expired() = SpeculativePlannerChecks.expired()
    @Test fun cancelled() = SpeculativePlannerChecks.cancelled()
    @Test fun failed() = SpeculativePlannerChecks.failed()
    @Test fun notReady() = SpeculativePlannerChecks.notReady()
    @Test fun invalidOffer() = SpeculativePlannerChecks.invalidOffer()
    @Test fun busyWorker() = SpeculativePlannerChecks.busyWorker()
    @Test fun closed() = SpeculativePlannerChecks.closed()
}
