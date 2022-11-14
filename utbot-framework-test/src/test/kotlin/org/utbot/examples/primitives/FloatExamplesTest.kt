package org.utbot.examples.primitives

import org.junit.jupiter.api.Test
import org.utbot.testcheckers.eq
import org.utbot.testing.UtValueTestCaseChecker

internal class FloatExamplesTest : UtValueTestCaseChecker(testClass = FloatExamples::class) {
    @Test
    fun testFloatInfinity() {
        check(
            FloatExamples::floatInfinity,
            eq(3),
            { f, r -> f == Float.POSITIVE_INFINITY && r == 1 },
            { f, r -> f == Float.NEGATIVE_INFINITY && r == 2 },
            { f, r -> !f.isInfinite() && r == 3 },
        )
    }
}