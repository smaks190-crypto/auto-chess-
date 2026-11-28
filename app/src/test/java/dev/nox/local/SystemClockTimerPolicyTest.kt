package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class SystemClockTimerPolicyTest {
    private val samsung = SystemClockTimerPolicy.Handler("com.sec.android.app.clockpackage", "Timer", true)
    private val thirdParty = SystemClockTimerPolicy.Handler("other.app", "Timer", false)
    private val another = SystemClockTimerPolicy.Handler("other.clock", "Timer", true)

    @Test fun samsungOnlyUsesStockClockAndNeverUntrustedHandler() {
        assertEquals(samsung, SystemClockTimerPolicy.select(listOf(thirdParty, another, samsung), "Samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(another, thirdParty), "samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(samsung.copy(system = false)), "samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(samsung, samsung.copy(activity = "Other")), "samsung"))
    }

    @Test fun galaxyStoreClockWithVerifiedSignerWorksWithoutSystemFlag() {
        val installed = samsung.copy(system = false, samsungSigned = true)
        assertEquals(installed, SystemClockTimerPolicy.select(listOf(installed, thirdParty), "Samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(installed.copy(samsungSigned = false)), "Samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(thirdParty.copy(samsungSigned = true)), "Samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(installed, installed.copy(activity = "Other")), "Samsung"))
        assertNull(SystemClockTimerPolicy.select(listOf(installed), "Google"))
    }

    @Test fun otherDevicesRequireUniqueSystemHandler() {
        assertEquals(another, SystemClockTimerPolicy.select(listOf(thirdParty, another), "Google"))
        assertNull(SystemClockTimerPolicy.select(listOf(thirdParty), "Google"))
        assertNull(SystemClockTimerPolicy.select(listOf(samsung, another), "Google"))
    }

    @Test fun durationBoundAndPreviousRegression() {
        assertEquals(60, LockedAssistantPolicy.timerSeconds("Поставь таймер на минуту"))
        assertEquals(60, LockedAssistantPolicy.timerSeconds("Set a timer for 1 minute"))
        assertTrue(SystemClockTimerPolicy.durationAllowed(60))
        assertTrue(SystemClockTimerPolicy.durationAllowed(1))
        assertTrue(SystemClockTimerPolicy.durationAllowed(3600))
        for (invalid in listOf(0, -1, 3601)) assertFalse(SystemClockTimerPolicy.durationAllowed(invalid))
    }
}
