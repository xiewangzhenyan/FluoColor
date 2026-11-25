package com.muc.fluocolorquant.domain.spectrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumProcessingConfigSnapshotTest {

    @Test
    fun encodeAndResolve_returnsFrozenConfiguration() {
        val expected = SpectrumProcessingConfig(
            minWavelength = 430.0,
            maxWavelength = 720.0,
            smoothingLevel = 5,
            sensitivity = SpectrumProcessingConfig.SENSITIVITY_HIGH
        )

        val json = SpectrumProcessingConfigSnapshot.encode(expected)
        val resolved = SpectrumProcessingConfigSnapshot.resolve(
            listOf(
                json to SpectrumProcessingConfig.PROCESSOR_VERSION,
                json to SpectrumProcessingConfig.PROCESSOR_VERSION
            )
        )

        assertEquals(SpectrumProcessingConfigOrigin.FROZEN, resolved.origin)
        assertEquals(expected, resolved.config)
        assertEquals(SpectrumProcessingConfig.PROCESSOR_VERSION, resolved.processorVersion)
    }

    @Test
    fun resolve_withoutSnapshot_usesDeterministicLegacyDefaults() {
        val resolved = SpectrumProcessingConfigSnapshot.resolve(
            listOf(null to null, null to null)
        )

        assertEquals(SpectrumProcessingConfigOrigin.LEGACY_DEFAULT, resolved.origin)
        assertEquals(SpectrumProcessingConfig.LEGACY_DEFAULT, resolved.config)
        assertEquals("legacy-unversioned", resolved.processorVersion)
    }

    @Test
    fun decode_rejectsOutOfRangeOrUnknownConfiguration() {
        val invalidRange = """{"schemaVersion":1,"minWavelength":800.0,"maxWavelength":400.0,"smoothingLevel":3,"sensitivity":"Medium"}"""
        val unknownSensitivity = """{"schemaVersion":1,"minWavelength":400.0,"maxWavelength":800.0,"smoothingLevel":3,"sensitivity":"Extreme"}"""

        assertNull(SpectrumProcessingConfigSnapshot.decode(invalidRange))
        assertNull(SpectrumProcessingConfigSnapshot.decode(unknownSensitivity))
    }

    @Test
    fun resolve_mixedOrDifferentSnapshots_failsClosedToLegacyConfiguration() {
        val first = SpectrumProcessingConfigSnapshot.encode(
            SpectrumProcessingConfig.LEGACY_DEFAULT
        )
        val second = SpectrumProcessingConfigSnapshot.encode(
            SpectrumProcessingConfig.LEGACY_DEFAULT.copy(smoothingLevel = 7)
        )

        val mixed = SpectrumProcessingConfigSnapshot.resolve(
            listOf(first to SpectrumProcessingConfig.PROCESSOR_VERSION, null to null)
        )
        val inconsistent = SpectrumProcessingConfigSnapshot.resolve(
            listOf(
                first to SpectrumProcessingConfig.PROCESSOR_VERSION,
                second to SpectrumProcessingConfig.PROCESSOR_VERSION
            )
        )

        assertEquals(SpectrumProcessingConfigOrigin.INVALID_SNAPSHOT, mixed.origin)
        assertEquals(SpectrumProcessingConfigOrigin.INCONSISTENT_SNAPSHOTS, inconsistent.origin)
        assertTrue(mixed.config.isValid())
        assertEquals(SpectrumProcessingConfig.LEGACY_DEFAULT, inconsistent.config)
    }

    @Test
    fun resolve_partialSnapshot_isCorruptionInsteadOfLegacyHistory() {
        val versionOnly = SpectrumProcessingConfigSnapshot.resolve(
            listOf(null to SpectrumProcessingConfig.PROCESSOR_VERSION)
        )
        val configOnly = SpectrumProcessingConfigSnapshot.resolve(
            listOf(
                SpectrumProcessingConfigSnapshot.encode(SpectrumProcessingConfig.LEGACY_DEFAULT) to null
            )
        )

        assertEquals(SpectrumProcessingConfigOrigin.INVALID_SNAPSHOT, versionOnly.origin)
        assertEquals(SpectrumProcessingConfigOrigin.INVALID_SNAPSHOT, configOnly.origin)
    }
}
