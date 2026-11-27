package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryTest {
    @Test fun cpuIsNormalizedAcrossAvailableCores() {
        assertEquals(12.5, DeviceTelemetry.processCpuPercent(2000, 2000, 8)!!, .001)
        assertEquals(100.0, DeviceTelemetry.processCpuPercent(16000, 2000, 8)!!, .001)
        assertEquals(0.0, DeviceTelemetry.processCpuPercent(0, 2000, 8)!!, .001)
    }
    @Test fun invalidOrStaleIntervalsAreNotShownAsZero() {
        assertNull(DeviceTelemetry.processCpuPercent(-1, 2000, 8))
        assertNull(DeviceTelemetry.processCpuPercent(20, 0, 8))
        assertNull(DeviceTelemetry.processCpuPercent(20, 100, 8))
        assertNull(DeviceTelemetry.processCpuPercent(20, 11000, 8))
        assertNull(DeviceTelemetry.processCpuPercent(20, 2000, 0))
        assertNull(DeviceTelemetry.processCpuPercent(18000, 2000, 8))
    }
}
