package com.starwake.core.content

import com.starwake.core.game.AsteroidField
import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.FontData

/**
 * CPU-side content generated once at startup and kept for the app's lifetime, so GPU resources can
 * be rebuilt instantly when Android destroys the GL context (app switch, screen off). The ships are
 * loaded on demand (ShipModel, see Game.selectShip).
 */
class Content(fontMetrics: String, val fontAtlas: ByteArray, val system: StarSystem = StarSystem()) {
    val font = FontData.parse(fontMetrics)
    val rocks: Array<FloatArray> = Array(ROCK_VARIANTS) { AsteroidMesh.build(it * 17 + 5, 2) }
    val field = AsteroidField(1234L, MAX_ASTEROIDS, ROCK_VARIANTS).also { f ->
        // collide against each rock's median surface radius rather than its bounding scale
        f.setCollisionFactors(FloatArray(ROCK_VARIANTS) { AsteroidMesh.medianRadius(rocks[it]) })
        // the field was generated around the home position; place it there in the real system
        f.origin.set(system.fieldOrigin)
    }
    val stars: FloatArray = StarField.build(MAX_STARS)
    val dust: Pair<FloatArray, ShortArray> = DustField.build(MAX_DUST)
    val stationMeshes: Array<FloatArray> = Array(StationMesh.MODELS) { StationMesh.build(it) }
    val stationLights: Array<FloatArray> = Array(StationMesh.MODELS) { StationMesh.lights(it) }

    companion object {
        const val ROCK_VARIANTS = 3
        const val MAX_ASTEROIDS = 420
        const val MAX_STARS = 6500
        const val MAX_DUST = 700
    }
}
