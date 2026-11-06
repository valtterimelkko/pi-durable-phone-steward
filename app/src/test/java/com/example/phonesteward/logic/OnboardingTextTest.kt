package com.example.phonesteward.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** a permission row explains why, and a button does the work; it never spells out a Settings path. */
class OnboardingTextTest {

    @Test
    fun `no step tells the owner to hunt through Settings menus`() {
        Onboarding.Step.entries.forEach { step ->
            assertFalse("${step.name}: '${step.how}' spells out a path", step.how.contains("→") || step.how.contains("Settings"))
            assertTrue("${step.name} still explains why it is needed", step.how.length >= 20)
        }
    }
}
