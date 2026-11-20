package com.freescanner.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    @Test fun acceptsFullAndPerspectiveCrops() {
        assertTrue(Crop.Full.isValid())
        assertTrue(Crop(listOf(Corner(.15f, .1f), Corner(.9f, .2f), Corner(.8f, .95f), Corner(.05f, .8f))).isValid())
    }

    @Test fun rejectsSelfIntersection() {
        assertFalse(Crop(listOf(Corner(0f, 0f), Corner(1f, 1f), Corner(1f, 0f), Corner(0f, 1f))).isValid())
    }

    @Test fun rejectsDegenerateAndInvertedShapes() {
        assertFalse(Crop(listOf(Corner(0f, 0f), Corner(.5f, 0f), Corner(1f, 0f), Corner(0f, 1f))).isValid())
        assertFalse(Crop(Crop.Full.corners.reversed()).isValid())
        assertFalse(Crop(listOf(Corner(.1f, .1f), Corner(.11f, .1f), Corner(.11f, .11f), Corner(.1f, .11f))).isValid())
        assertFalse(Crop(listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(.4f, .4f), Corner(0f, 1f))).isValid())
    }

    @Test fun rejectsOutOfBoundsNonfiniteAndWrongCornerCounts() {
        for (point in listOf(Corner(-.01f, 0f), Corner(0f, 1.01f), Corner(Float.NaN, 0f), Corner(0f, Float.POSITIVE_INFINITY))) {
            assertFalse(Crop(Crop.Full.corners.toMutableList().apply { this[0] = point }).isValid())
        }
        assertFalse(Crop(emptyList()).isValid())
        assertFalse(Crop(Crop.Full.corners.take(3)).isValid())
    }
}
