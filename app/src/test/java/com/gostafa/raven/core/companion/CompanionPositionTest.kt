package com.gostafa.raven.core.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPositionTest {
    @Test fun savedEdgeAndVerticalPositionSurviveScreenSizeChanges() {
        assertEquals(344 to 372, companionCoordinates(400, 800, 56, CompanionPosition(true, 0.5f)))
        assertEquals(0 to 344, companionCoordinates(800, 400, 56, CompanionPosition(false, 1f)))
    }
    @Test fun coordinatesStayInsideEvenOnSmallScreensOrInvalidSavedFractions() {
        assertEquals(0 to 0, companionCoordinates(20, 20, 56, CompanionPosition(true, 2f)))
        assertEquals(344 to 0, companionCoordinates(400, 800, 56, CompanionPosition(true, -1f)))
    }
}
