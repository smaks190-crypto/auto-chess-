package dev.nox.local

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.*
import org.junit.Test

class NanoProbeTest {
    @Test fun availabilityIsReportedWithoutAssumingDownloadOrInference() {
        assertTrue(NanoProbe.statusLabel(FeatureStatus.AVAILABLE).startsWith("AVAILABLE"))
        assertTrue(NanoProbe.statusLabel(FeatureStatus.DOWNLOADABLE).contains("загрузка не начата"))
        assertTrue(NanoProbe.statusLabel(FeatureStatus.DOWNLOADING).startsWith("DOWNLOADING"))
        assertTrue(NanoProbe.statusLabel(FeatureStatus.UNAVAILABLE).startsWith("UNAVAILABLE"))
        assertTrue(NanoProbe.statusLabel(-1).contains("Неизвестный"))
    }
}
