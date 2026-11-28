package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AssistantAutoListenTest {
    @Test fun busyKernelDoesNotConsumeExplicitInvocation() {
        val deadline = 121000L
        assertEquals(AssistantAutoListen.Decision.WAIT, AssistantAutoListen.decide(deadline, 1001, true, false))
        assertEquals(AssistantAutoListen.Decision.START, AssistantAutoListen.decide(deadline, 20000, true, true))
    }

    @Test fun closedLockedExpiredOrConsumedInvocationNeverStarts() {
        assertEquals(AssistantAutoListen.Decision.CANCEL, AssistantAutoListen.decide(1000, 999, false, true))
        assertEquals(AssistantAutoListen.Decision.CANCEL, AssistantAutoListen.decide(1000, 1000, true, true))
        assertEquals(AssistantAutoListen.Decision.CANCEL, AssistantAutoListen.decide(0, 1000, true, true))
    }
}
