package com.starwake.core.game

import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.terrain.SurfaceProfiles
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sol, from the Roblox game's `ShipSolData` (03-world.md 2.1): the numbers below are the Lua's, in
 * studs, and [S] turns every length into metres in this one place (DECISIONS 1). At S = 3000 every
 * rocky body has its real size: Earth 6,360 km, Luna 1,740 km at 384,000 km, Mars 3,390 km. The
 * giants are 0.4× real and the planets' orbits 1/60 of real, as in the Roblox game.
 *
 * The bodies stand still at a fixed epoch (DECISIONS 6 allows it as the first step): each planet at
 * [Planet.phase] on its circle in the XZ plane, each moon likewise around its planet. Planets never
 * spin (DECISIONS 7), so the terrain is planet-fixed and a landed ship stays where it is.
 *
 * The solid bodies carry what the terrain engine and the flight physics need: the Roblox body
 * class and seed (the surface style and its height function), the surface gravity in g, and the
 * air (ground density and scale height; the real values, so drag and entry heating feel right).
 */
object SolData {
    /** Metres per stud (DECISIONS 1). */
    const val S = 3000.0

    private class Moon(
        val name: String, val radius: Double, val orbit: Double, val phase: Double, val gravityG: Double,
        val type: StarSystem.BodyType = StarSystem.BodyType.ROCK, val tint: FloatArray? = null,
        val atmo: FloatArray? = null, val airThickness: Double = 0.0, val airDensity: Double = 0.0, val airScaleHeight: Double = 8500.0,
    )

    private class Planet(
        val name: String, val radius: Double, val orbit: Double, val phase: Double, val gravityG: Double,
        val bodyClass: String, val type: StarSystem.BodyType,
        /** Atmosphere colour (the impostor's scattering tint, linear), or null without one. */
        val atmo: FloatArray? = null,
        /** Height where the air ends (m, the surface profile's thickness; DECISIONS 17b), or the shell in radii for a giant (< 1). */
        val airThickness: Double = 0.0,
        val airDensity: Double = 0.0, val airScaleHeight: Double = 8500.0,
        val ringInner: Double = 0.0, val ringOuter: Double = 0.0, val tiltDeg: Float = 0f,
        val tint: FloatArray? = null,
        val moons: List<Moon> = emptyList(),
    )

    private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

    /** A profile colour (sRGB 0..1) as linear RGB (the engine's albedos are sRGB², as the terrain's). */
    private fun linear(c: com.starwake.core.terrain.Rgb) = floatArrayOf((c.r * c.r).toFloat(), (c.g * c.g).toFloat(), (c.b * c.b).toFloat())

    private val PLANETS = listOf(
        Planet("Mercury", 810.0, 460_000.0, 0.9, 0.377, "Rocky Body", StarSystem.BodyType.ROCK),
        Planet(
            "Venus", 2020.0, 677_710.0, 2.5, 0.904, "Greenhouse World", StarSystem.BodyType.ROCK,
            atmo = floatArrayOf(1.0f, 0.86f, 0.55f), airThickness = 40_000.0, airDensity = 65.0, airScaleHeight = 15_900.0,
            tint = rgb(216, 190, 140),
        ),
        Planet(
            "Earth", 2120.0, 828_660.0, 4.1, 1.0, "Terrestrial World", StarSystem.BodyType.TERRAN,
            atmo = floatArrayOf(0.35f, 0.55f, 1.0f), airThickness = 26_000.0, airDensity = 1.225, airScaleHeight = 8_500.0,
            moons = listOf(Moon("Luna", 580.0, 128_000.0, 0.75, 0.165)),
        ),
        Planet(
            "Mars", 1130.0, 1_076_040.0, 5.2, 0.378, "Rocky Body", StarSystem.BodyType.ROCK,
            atmo = floatArrayOf(0.85f, 0.6f, 0.45f), airThickness = 22_000.0, airDensity = 0.020, airScaleHeight = 11_100.0,
            tint = rgb(168, 96, 62),
            moons = listOf(
                Moon("Phobos", 150.0, 3_600.0, 1.9, 0.0006),
                Moon("Deimos", 150.0, 7_800.0, 4.4, 0.0003),
            ),
        ),
        Planet(
            "Jupiter", 9320.0, 2_303_850.0, 1.7, 2.528, "Gas Giant", StarSystem.BodyType.GAS,
            atmo = rgb(230, 200, 160), airThickness = 0.015, ringInner = 1.45, ringOuter = 1.72, tiltDeg = 3f,
            moons = listOf(
                Moon("Io", 610.0, 55_000.0, 0.4, 0.183, tint = rgb(216, 196, 116)),
                Moon("Europa", 520.0, 87_600.0, 2.2, 0.134, type = StarSystem.BodyType.ICE),
                Moon("Ganymede", 880.0, 140_000.0, 3.9, 0.146),
                Moon("Callisto", 800.0, 245_000.0, 5.5, 0.126),
            ),
        ),
        Planet(
            "Saturn", 7764.0, 3_354_380.0, 3.3, 1.065, "Gas Giant", StarSystem.BodyType.GAS,
            atmo = rgb(238, 218, 176), airThickness = 0.015, ringInner = 1.24, ringOuter = 2.27, tiltDeg = 26.7f,
            tint = rgb(226, 204, 158),
            moons = listOf(
                Moon(
                    "Titan", 860.0, 157_600.0, 1.2, 0.138, tint = rgb(196, 150, 96),
                    atmo = floatArrayOf(0.95f, 0.62f, 0.28f), airThickness = 36_000.0, airDensity = 5.4, airScaleHeight = 20_000.0,
                ),
            ),
        ),
        Planet(
            "Uranus", 3380.0, 5_174_850.0, 0.2, 0.886, "Ice Giant", StarSystem.BodyType.GAS,
            atmo = rgb(170, 230, 235), airThickness = 0.015, ringInner = 1.64, ringOuter = 2.0, tiltDeg = 82f,
            tint = rgb(150, 214, 220),
        ),
        Planet(
            "Neptune", 3284.0, 6_836_280.0, 5.9, 1.137, "Ice Giant", StarSystem.BodyType.GAS,
            atmo = rgb(110, 150, 255), airThickness = 0.015, tint = rgb(62, 96, 200),
            moons = listOf(Moon("Triton", 450.0, 47_300.0, 2.8, 0.079, type = StarSystem.BodyType.ICE)),
        ),
    )

    /** id, name, host, stand-in model, distance (studs), angle and elevation (degrees): 03-world.md 2.5.1. */
    private class Port(val name: String, val host: String, val model: Int, val distance: Double, val angle: Double, val elevation: Double)

    private val PORTS = listOf(
        // the Lua has MERIDIAN at 90° (over the terminator); at the fixed epoch it hangs at 55°, over the day side
        Port("MERIDIAN STATION", "Earth", StationMesh.RING_STATION, 6600.0, 55.0, 24.0),
        Port("TRANQUILITY YARDS", "Luna", StationMesh.OUTPOST, 3400.0, 200.0, -32.0),
        Port("ARES DEEP", "Mars", StationMesh.OUTPOST, 4600.0, 60.0, 28.0),
        Port("GALILEAN REFINERY", "Jupiter", StationMesh.RING_STATION, 20000.0, 120.0, 30.0),
        Port("TITAN HYDROPONICS", "Titan", StationMesh.OUTPOST, 4100.0, 300.0, 34.0),
        Port("HELIOS FORGE", "Mercury", StationMesh.RING_STATION, 4400.0, 270.0, -26.0),
    )

    /** Fills [system] with Sol. */
    internal fun build(system: StarSystem) {
        system.name = "SOL"
        system.hasField = false
        system.homeStation = "MERIDIAN STATION"
        system.edgeRadius = 2.6e10
        val bodies = system.bodies
        bodies += StarSystem.Body(
            "SOL", StarSystem.BodyType.STAR, 4800.0 * S, DVec3(), Quat(), 0f, false,
            atmo = floatArrayOf(1f, 0.75f, 0.43f), atmoThickness = 0.08f, gravityG = 0.5, parent = -1,
        )
        for ((index, p) in PLANETS.withIndex()) {
            val orbit = p.orbit * S
            // a slightly different plane per planet, so the system is not a flat disc from the side
            val incl = 0.012 * sin(index * 2.4 + 0.6)
            val pos = DVec3(orbit * cos(p.phase), orbit * incl, orbit * sin(p.phase))
            val radius = p.radius * S
            val giant = p.type == StarSystem.BodyType.GAS
            val planetIndex = bodies.size
            val seed = index + 1
            // the cloud deck of the body's surface profile (worlds with air only)
            val deck = if (!giant && p.airDensity > 0.0) SurfaceProfiles.forBody(p.name, p.bodyClass, seed, "Sol", true).clouds else null
            bodies += StarSystem.Body(
                p.name.uppercase(), p.type, radius, pos, Quat().setAxisAngle(0f, 0f, 1f, p.tiltDeg * (PI / 180.0).toFloat()), 0f,
                p.ringOuter > p.ringInner, p.ringInner * radius, p.ringOuter * radius,
                atmo = p.atmo ?: floatArrayOf(0.6f, 0.6f, 0.6f),
                // the visible limb is 3 × the air's thickness (DECISIONS 17b); giants give the shell in radii
                atmoThickness = if (giant) p.airThickness.toFloat() else (3.0 * p.airThickness / radius).toFloat(),
                seed = seed,
                bodyClass = if (giant) null else p.bodyClass,
                gravityG = p.gravityG, airDensity = p.airDensity, airScaleHeight = p.airScaleHeight,
                parent = 0, tint = p.tint,
                cloudCover = deck?.cover?.toFloat() ?: 0f,
                cloudHeight = if (deck != null) (deck.bottom + deck.top) * 0.5 else 2500.0,
                cloudColor = if (deck != null) linear(deck.colour) else floatArrayOf(1f, 1f, 1f),
            )
            for ((m, moon) in p.moons.withIndex()) {
                val mo = moon.orbit * S
                val mr = moon.radius * S
                val moonDeck = if (moon.airDensity > 0.0) SurfaceProfiles.forBody(moon.name, "Moon", seed * 31 + m + 1, "Sol", true).clouds else null
                val mp = DVec3(pos.x + mo * cos(moon.phase), pos.y + mo * 0.05 * sin(moon.phase * 1.7 + m), pos.z + mo * sin(moon.phase))
                bodies += StarSystem.Body(
                    moon.name.uppercase(), moon.type, mr, mp, Quat(), 0f, false,
                    atmo = moon.atmo ?: floatArrayOf(0.6f, 0.6f, 0.6f),
                    atmoThickness = (3.0 * moon.airThickness / mr).toFloat(),
                    // moon seeds: planetIndex · 31 + moonIndex, both from 1 (03-world.md 2.1)
                    seed = seed * 31 + m + 1,
                    bodyClass = "Moon",
                    gravityG = moon.gravityG, airDensity = moon.airDensity, airScaleHeight = moon.airScaleHeight,
                    parent = planetIndex, tint = moon.tint,
                    cloudCover = moonDeck?.cover?.toFloat() ?: 0f,
                    cloudHeight = if (moonDeck != null) (moonDeck.bottom + moonDeck.top) * 0.5 else 2500.0,
                    cloudColor = if (moonDeck != null) linear(moonDeck.colour) else floatArrayOf(1f, 1f, 1f),
                )
            }
        }

        val toStar = Vec3()
        val dir = Vec3()
        val up = Vec3()
        for (port in PORTS) {
            val hostIndex = bodies.indexOfFirst { it.name == port.host.uppercase() }
            val host = bodies[hostIndex]
            // `ShipStations.offset`: Distance from the host, Angle from the host→star direction in the
            // orbit plane, raised by Elevation
            val l = sqrt(host.pos.x * host.pos.x + host.pos.z * host.pos.z)
            toStar.set((-host.pos.x / l).toFloat(), 0f, (-host.pos.z / l).toFloat())
            val a = port.angle * PI / 180.0
            val e = port.elevation * PI / 180.0
            val dx = toStar.x * cos(a) + toStar.z * sin(a)
            val dz = -toStar.x * sin(a) + toStar.z * cos(a)
            val d = port.distance * S
            val pos = DVec3(host.pos.x + dx * cos(e) * d, host.pos.y + sin(e) * d, host.pos.z + dz * cos(e) * d)
            // the slot looks at the host, a little off its centre: a launch flies out towards the planet
            dir.set((host.pos.x - pos.x).toFloat(), (host.pos.y - pos.y).toFloat(), (host.pos.z - pos.z).toFloat()).normalize()
            up.set(0f, 1f, 0f).addScaled(dir, -dir.y).normalize()
            dir.addScaled(up, 0.22f).normalize()
            up.addScaled(dir, -up.dot(dir)).normalize()
            val rot = Quat().lookRotation(dir.x, dir.y, dir.z, up.x, up.y, up.z)
            system.stations += StarSystem.Station(port.name, port.model, pos, rot, StationMesh.radius(port.model).toDouble(), hostIndex)
        }

        // home: outside MERIDIAN's slot and beside its axis, looking past the station at the Earth
        val meridian = system.stations[0]
        meridian.rot.rotate(0f, 0f, -1f, dir)
        meridian.rot.rotate(0f, 1f, 0f, up)
        val right = Vec3().cross(dir, up).normalize()
        system.home.set(
            meridian.pos.x + dir.x * 2600.0 + up.x * 250.0 + right.x * 900.0,
            meridian.pos.y + dir.y * 2600.0 + up.y * 250.0 + right.y * 900.0,
            meridian.pos.z + dir.z * 2600.0 + up.z * 250.0 + right.z * 900.0,
        )
        system.homeRot.set(meridian.rot)
        system.fieldOrigin.set(system.home)
    }
}
