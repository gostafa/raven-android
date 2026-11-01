package com.gostafa.raven.features.library

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class CardAppearanceTest {
    @Test fun recallColorsMatchRavenAnchorsAndClamp() {
        fun assertColor(expected: Color, percent: Int) {
            val actual = recallColor(percent)
            assertEquals(expected.red, actual.red, 0.002f)
            assertEquals(expected.green, actual.green, 0.002f)
            assertEquals(expected.blue, actual.blue, 0.002f)
        }
        assertColor(Color(0xFFF87171), -20)
        assertColor(Color(0xFFF87171), 50)
        assertColor(Color(0xFFFACC15), 80)
        assertColor(Color(0xFF4ADE80), 100)
        assertColor(Color(0xFF4ADE80), 120)
        assertNotEquals(recallColor(50), recallColor(65))
        assertNotEquals(recallColor(65), recallColor(80))
    }

    @Test fun timeUsesElapsedMinutesAndClampsNegativeValues() {
        assertEquals("0:00", previewTime(-1))
        assertEquals("0:01", previewTime(1_999))
        assertEquals("1:05", previewTime(65_000))
        assertEquals("61:01", previewTime(3_661_000))
    }
}
