package com.starwake.core.content

import com.starwake.core.game.AsteroidField
import com.starwake.core.gfx.FontData

/**
 * CPU-side content generated once at startup and kept for the app's lifetime, so GPU resources can
 * be rebuilt instantly when Android destroys the GL context (app switch, screen off).
 */
class Content(fontMetrics: String, val fontAtlas: ByteArray) {
    val font = FontData.parse(fontMetrics)
    val ship: FloatArray = KiteShip.build()
    val rocks: Array<FloatArray> = Array(ROCK_VARIANTS) { AsteroidMesh.build(it * 17 + 5, 2) }
    val field = AsteroidField(1234L, MAX_ASTEROIDS, ROCK_VARIANTS)
    val stars: FloatArray = StarField.build(MAX_STARS)
    val dust: Pair<FloatArray, ShortArray> = DustField.build(MAX_DUST)
    val sphere: Pair<FloatArray, ShortArray> = Primitives.uvSphere(72, 44)
    val ring: Pair<FloatArray, ShortArray> = Primitives.annulus(180, 1.40f, 2.34f)

    companion object {
        const val ROCK_VARIANTS = 3
        const val MAX_ASTEROIDS = 420
        const val MAX_STARS = 6500
        const val MAX_DUST = 700
    }
}
