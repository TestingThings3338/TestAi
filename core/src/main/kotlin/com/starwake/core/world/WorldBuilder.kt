package com.starwake.core.world

import com.starwake.core.content.StationMesh
import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import kotlin.math.PI
import kotlin.math.ceil

/**
 * `ShipSystemBuilder.build` for the port (03-world.md 2.2.6-2.2.8, 9.1): a [SystemDef] in studs →
 * the runtime [StarSystem] in metres. The one place where [WorldScale.S] is applied to world
 * lengths (radii, orbits, rings, star radii, station distances, the field); times stay, so orbital
 * speeds become ×S. Phases, orbit tilts and yaws come from the Roblox seeds and draw order
 * ([WorldRandom]); the rest positions are computed here and the stations hang from them.
 */
object WorldBuilder {
    /** `Ship.FieldRadius`, studs: the play field around the star (×S: 27 Gm). */
    const val FIELD_RADIUS = 9_000_000.0

    private val GAS_CLASSES = setOf("Gas Giant", "Ice Giant")

    /** Sol at S = 3000 with its six stations, the clock at 0 (rest positions). */
    fun sol(): StarSystem = build(SolData.SOL, 1, StationDirectory.SOL)

    /**
     * Builds [def] as system [index] with [stations] (their hosts matched by name, `findHost`; an
     * unknown host skips the station, as Roblox warns and skips it). The clock starts at 0.
     */
    fun build(def: SystemDef, index: Int, stations: List<StationDef> = emptyList()): StarSystem {
        val s = WorldScale.S
        val bodies = ArrayList<StarSystem.Body>()
        val rest = ArrayList<DVec3>()

        // ---------- the star (2.2.7)
        val st = def.star
        val starR = st.radius * s
        bodies += StarSystem.Body(
            name = st.name, cls = "Star " + (st.cls ?: "G"), type = StarSystem.BodyType.STAR,
            radius = starR, parent = -1, seed = 1, isStar = true,
            collisionRadius = starR, minApproachSpeed = 40f, gravityG = 0.5, landable = false,
            massShadowFactor = 4.0,
            heatRadius = (st.heatRadius ?: (st.radius * 1.6)) * s,
            massShadowRadius = (st.massShadowRadius ?: (st.radius * 4.0)) * s,
            coronaScale = st.coronaScale ?: 1.12,
            colour = st.colour, corona = st.corona,
            look = -1,
        )
        rest += DVec3()

        // ---------- planets and moons (2.2.8)
        for ((k, planet) in def.planets.withIndex()) {
            val index1 = k + 1
            val seed = planet.seed ?: index1
            val rng = WorldRandom(seed * 7919L + 13L)
            val phase = planet.phase ?: rng.nextNumber() // draw 1
            val yaw = WorldRandom(seed * 6151L + 7L).nextNumber() * 2.0 * PI
            val tilt = planet.tilt ?: rng.nextNumber(-1.5, 1.5) // draw 2 (degrees)
            val ringTilt = planet.ring?.tiltDeg ?: 0.0
            val rot = Quat().setAxisAngle(0f, 0f, 1f, Math.toRadians(ringTilt).toFloat())
            rot.mul(rot, Quat().setAxisAngle(0f, 1f, 0f, yaw.toFloat()))
            val gas = planet.cls in GAS_CLASSES
            val radius = planet.radius * s
            val ring = planet.ring
            val air = if (planet.atmosphere) planet.atmosphereColour ?: planet.colour else 0
            val limb = BodyLooks.limbHeight(planet.name, planet.cls, planet.atmosphere)
            val body = StarSystem.Body(
                name = planet.name, cls = planet.cls, type = BodyLooks.type(planet.cls),
                radius = radius, parent = 0, seed = seed,
                collisionRadius = barrier(planet.radius) * s, minApproachSpeed = 14f,
                gravityG = planet.gravityG ?: 0.5,
                landable = !gas,
                landingBlocked = planet.landingBlocked ?: if (gas) StarSystem.NO_SOLID_SURFACE else null,
                massShadowFactor = 5.0,
                orbitRadius = planet.orbit * s, orbitPeriod = Orbits.period(planet.orbit, planet.speed),
                orbitPhase = phase, orbitTilt = tilt,
                colour = planet.colour, hasAtmosphere = planet.atmosphere, atmosphereColour = air,
                rot = rot,
                hasRings = ring != null,
                ringInner = if (ring != null) radius * ring.inner else 0.0,
                ringOuter = if (ring != null) radius * ring.outer else 0.0,
                ringColour = ring?.colour ?: 0,
                ringDensity = if (ring != null) (1.0 - ring.transparency).toFloat() else 0f,
                ringProfile = if (ring != null) RingProfiles.forPlanet(planet.name).let { if (it >= 0) it else genericRing(seed) } else -1,
                atmo = tint(air),
                atmoThickness = (limb / radius).toFloat(),
                atmoDepth = BodyLooks.airDepth(planet.name, planet.cls),
                look = BodyLooks.look(planet.name, planet.cls),
            )
            if (ring != null) {
                val t = Math.toRadians(ringTilt)
                body.ringNormal.set((-kotlin.math.sin(t)).toFloat(), kotlin.math.cos(t).toFloat(), 0f)
            }
            val planetIndex = bodies.size
            bodies += body
            val planetRest = Orbits.addRest(body.orbitRadius, phase, DVec3())
            rest += planetRest

            for ((m0, moon) in planet.moons.withIndex()) {
                val m = m0 + 1
                val mrng = WorldRandom(seed * 104729L + m)
                val mphase = moon.phase ?: mrng.nextNumber()
                val moonSeed = seed * 31 + m
                val myaw = WorldRandom(moonSeed * 6151L + 7L).nextNumber() * 2.0 * PI
                val mtilt = moon.tilt ?: mrng.nextNumber(-5.0, 5.0)
                val mcls = moon.cls ?: "Moon"
                val mr = moon.radius * s
                // moons have no air in Roblox; Titan keeps its orange limb (DECISIONS 17b)
                val mlimb = BodyLooks.limbHeight(moon.name, mcls, false)
                val mair = if (moon.name == "Titan") BodyLooks.TITAN_AIR else 0
                val mbody = StarSystem.Body(
                    name = moon.name, cls = mcls, type = BodyLooks.type(mcls),
                    radius = mr, parent = planetIndex, seed = moonSeed,
                    collisionRadius = barrier(moon.radius) * s, minApproachSpeed = 12f,
                    gravityG = moon.gravityG ?: 0.5,
                    landable = mcls !in GAS_CLASSES, landingBlocked = null,
                    massShadowFactor = 4.0,
                    orbitRadius = moon.orbit * s, orbitPeriod = Orbits.period(moon.orbit, moon.speed),
                    orbitPhase = mphase, orbitTilt = mtilt,
                    colour = moon.colour, hasAtmosphere = false, atmosphereColour = mair,
                    rot = Quat().setAxisAngle(0f, 1f, 0f, myaw.toFloat()),
                    atmo = tint(mair),
                    atmoThickness = (mlimb / mr).toFloat(),
                    atmoDepth = BodyLooks.airDepth(moon.name, mcls),
                    look = BodyLooks.look(moon.name, mcls),
                )
                bodies += mbody
                rest += Orbits.addRest(mbody.orbitRadius, mphase, DVec3().set(planetRest))
            }
        }
        for (i in bodies.indices) bodies[i].restPos.set(rest[i])

        // ---------- stations (2.5.4, 2.5.5): offsets from the hosts' rest positions, the star at the origin
        val built = ArrayList<StarSystem.Station>()
        val reach = DoubleArray(bodies.size)
        for (def2 in stations) {
            val host = bodies.indexOfFirst { it.name == def2.host }
            if (host < 0) continue
            val h = bodies[host].restPos
            val sp = bodies[0].restPos
            val offset = StationDirectory.offset(def2, h.x, h.y, h.z, sp.x, sp.y, sp.z, s, DVec3())
            val model = StationDirectory.standIn(def2.model)
            built += StarSystem.Station(
                id = def2.id, name = def2.name, model = model, modelName = def2.model,
                rot = StationDirectory.rotation(offset.x, offset.y, offset.z, Quat()),
                radius = StationMesh.radius(model).toDouble(), parent = host, offset = offset,
                economy = def2.economy, shipyard = def2.shipyard, description = def2.description,
            )
            reach[host] = maxOf(reach[host], def2.distance * s)
        }

        // ---------- reference frames (2.9.4): Rc·(1 + shadow factor), and every hosted station inside
        for (i in bodies.indices) {
            val b = bodies[i]
            if (b.isStar) continue
            b.frameRadius = maxOf(b.collisionRadius * (1.0 + b.massShadowFactor), reach[i] * StarSystem.FRAME_HYSTERESIS)
        }

        // the light reference: the home station's host planet (Earth in Sol), else the first planet
        val homeHost = built.firstOrNull { it.id == StationDirectory.HOME_STATION }?.parent ?: -1
        var refBody = if (homeHost >= 0) homeHost else 1.coerceAtMost(bodies.size - 1)
        while (refBody > 0 && bodies[refBody].parent > 0) refBody = bodies[refBody].parent
        val refDistance = if (refBody > 0) bodies[refBody].orbitRadius else starR * 100.0

        val system = StarSystem(def.name, index, bodies, built, FIELD_RADIUS * s, refDistance)
        system.snapTo(0.0)
        return system
    }

    /** `CollisionRadius` of a planet or moon (studs): `ceil(1.105·R) + 6`. */
    fun barrier(radiusStuds: Double): Double = ceil(radiusStuds * 1.105) + 6.0

    /** Generic ring slot `Rings.Generic[abs(seed) % 7 + 1]` (2.7.3) for a generated ring. */
    private fun genericRing(seed: Int): Int = RingProfiles.GENERIC_FIRST + Math.floorMod(kotlin.math.abs(seed), 7)

    /** sRGB 0xRRGGBB → the linear tint the scattering model normalises. */
    private fun tint(rgb: Int): FloatArray {
        if (rgb == 0) return floatArrayOf(0.35f, 0.5f, 0.75f)
        return floatArrayOf(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
    }
}
