package com.starwake.core.content

import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.FontData
import com.starwake.core.world.WorldBuilder

/**
 * CPU-side content generated once at startup and kept for the app's lifetime, so GPU resources can
 * be rebuilt instantly when Android destroys the GL context (app switch, screen off). The ships are
 * loaded on demand (ShipModel, see Game.selectShip). The world is Sol (03-world.md); the old
 * asteroid field is gone (DECISIONS 13).
 */
class Content(fontMetrics: String, val fontAtlas: ByteArray, val system: StarSystem = WorldBuilder.sol()) {
    val font = FontData.parse(fontMetrics)
    /** The cloud decks' weather map and detail noise, built in the background from the first frame on. */
    val clouds = com.starwake.core.gfx.CloudField().also { it.start() }
    val stars: FloatArray = StarField.build(MAX_STARS)
    val dust: Pair<FloatArray, ShortArray> = DustField.build(MAX_DUST)
    val stationMeshes: Array<FloatArray> = Array(StationMesh.MODELS) { StationMesh.build(it) }
    val stationLights: Array<FloatArray> = Array(StationMesh.MODELS) { StationMesh.lights(it) }

    /** The ring-debris rock (one lumpy icosphere, unit size), drawn instanced and squashed per rock (RingDebris). */
    val debrisRock: FloatArray = AsteroidMesh.build(DEBRIS_ROCK_SEED, 2)

    companion object {
        const val MAX_STARS = 6500
        const val MAX_DUST = 700
        const val DEBRIS_ROCK_SEED = 84213
    }
}
