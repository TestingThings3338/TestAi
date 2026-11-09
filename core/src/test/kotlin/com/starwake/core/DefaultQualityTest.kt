package com.starwake.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultQualityTest {
    @kotlin.test.Test
    fun theFlagshipTiersStartOnUltra() {
        for (r in listOf("Adreno (TM) 740", "Adreno (TM) 750", "Adreno (TM) 830", "Immortalis-G715 MC11", "Immortalis-G925 MC12", "Mali-G720 MC8")) {
            kotlin.test.assertTrue(Game.ultraCapable(r), r)
        }
        for (r in listOf("Adreno (TM) 660", "Adreno (TM) 710", "Adreno (TM) 725", "Adreno (TM) 810", "Mali-G710 MC10", "Mali-G78 MP24", "Mesa llvmpipe", "")) {
            kotlin.test.assertFalse(Game.ultraCapable(r), r)
        }
    }

    private fun q(r: String) = Game.defaultQuality(r)

    @Test
    fun maliModelsAreComparedAsNumbers() {
        assertEquals(0, q("Mali-G52 MC2"))
        assertEquals(0, q("Mali-G57 MC3"))
        assertEquals(0, q("Mali-G68 MC4"))
        assertEquals(1, q("Mali-G76 MP12"))
        assertEquals(1, q("Mali-G78 MP24"))
        assertEquals(0, q("Mali-G310"))
        assertEquals(1, q("Mali-G610 MC6"))
        assertEquals(1, q("Mali-G615 MC6"))
        assertEquals(1, q("Mali-G620 MC6"))
        assertEquals(2, q("Mali-G710 MC10"))
        assertEquals(2, q("Immortalis-G715 MC11"))
        assertEquals(2, q("Immortalis-G925 MC12"))
        assertEquals(0, q("Mali-T880"))
    }

    @Test
    fun adrenoTiers() {
        assertEquals(0, q("Adreno (TM) 506"))
        assertEquals(0, q("Adreno (TM) 610"))
        assertEquals(1, q("Adreno (TM) 619"))
        assertEquals(1, q("Adreno (TM) 660"))
        assertEquals(1, q("Adreno (TM) 710"))
        assertEquals(2, q("Adreno (TM) 740"))
        assertEquals(1, q("Adreno (TM) 810"))
        // Xiaomi 15 (Snapdragon 8 Elite)
        assertEquals(2, q("Adreno (TM) 830"))
    }

    @Test
    fun othersDefaultToHighExceptPowerVr() {
        assertEquals(2, q("Samsung Xclipse 940"))
        assertEquals(0, q("PowerVR Rogue GE8320"))
        assertEquals(2, q("llvmpipe (LLVM 15.0.7, 256 bits)"))
    }
}
