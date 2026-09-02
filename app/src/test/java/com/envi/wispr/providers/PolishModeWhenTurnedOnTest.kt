package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome: when this fails, the user flips AI Polish on and lands on the wrong engine, or on a
 * provider that no longer exists. The six cells of the plan's §5 row 2.
 */
class PolishModeWhenTurnedOnTest {
    @Test fun theSixCells() {
        val expected = mapOf(
            (PolishMode.PROVIDER to true) to PolishMode.PROVIDER,
            (PolishMode.PROVIDER to false) to PolishMode.OFFLINE_S1,
            (PolishMode.OFFLINE_S1 to true) to PolishMode.OFFLINE_S1,
            (PolishMode.OFFLINE_S1 to false) to PolishMode.OFFLINE_S1,
            (PolishMode.OFF to true) to PolishMode.OFFLINE_S1,
            (PolishMode.OFF to false) to PolishMode.OFFLINE_S1,
        )
        assertEquals(PolishMode.entries.flatMap { m -> listOf(m to true, m to false) }.toSet(), expected.keys)
        expected.forEach { (cell, mode) -> assertEquals("$cell", mode, polishModeWhenTurnedOn(cell.first, cell.second)) }
    }

    @Test fun anAbsentOrUnknownOrOffStoredValueReadsAsThisPhone() {
        assertEquals(PolishMode.OFFLINE_S1, ProviderConfigurationRepository.decodeLastOnMode(emptyMap<String, Any>()))
        assertEquals(PolishMode.OFFLINE_S1, ProviderConfigurationRepository.decodeLastOnMode(mapOf("last_on_mode" to "garbage")))
        assertEquals(PolishMode.OFFLINE_S1, ProviderConfigurationRepository.decodeLastOnMode(mapOf("last_on_mode" to "OFF")))
        assertEquals(PolishMode.PROVIDER, ProviderConfigurationRepository.decodeLastOnMode(mapOf("last_on_mode" to "PROVIDER")))
    }
}
