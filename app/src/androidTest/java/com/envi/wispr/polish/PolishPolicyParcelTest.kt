package com.envi.wispr.polish

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness contract (#152): the hand-written parcel carries every variant across the `:polish` binder
 * unchanged. When the local case fails, the engine polishes under a tone the user did not pick.
 * Instrumented because `android.os.Parcel` is a stub on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class PolishPolicyParcelTest {

    private fun roundTrip(policy: PolishPolicy): PolishPolicy {
        val parcel = Parcel.obtain()
        try {
            policy.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return PolishPolicy.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun everyVariantSurvivesTheParcel() {
        val nonDefault = S1ControlSettings(S1Styling.CASUAL, S1Structure.PROSE, S1Context.EMAIL)
        val cases = listOf(
            PolishPolicy.Off,
            PolishPolicy.LocalS1(S1ControlSettings.DEFAULT),
            PolishPolicy.LocalS1(nonDefault),
            PolishPolicy.CloudUnconfigured,
            PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "llama3.2", "http://localhost:8080/v1", SelfHostedProtocol.OLLAMA),
        )
        for (policy in cases) assertEquals(policy, roundTrip(policy))
    }

    @Test fun everyPickOfEveryAxisSurvivesTheParcel() {
        for (styling in S1Styling.entries) for (structure in S1Structure.entries) for (context in S1Context.entries) {
            val policy = PolishPolicy.LocalS1(S1ControlSettings(styling, structure, context))
            assertEquals(policy, roundTrip(policy))
        }
    }
}
