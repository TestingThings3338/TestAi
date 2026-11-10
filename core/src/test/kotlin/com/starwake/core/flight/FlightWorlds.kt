package com.starwake.core.flight

import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat

/** Small hand-made worlds for the flight tests: bodies placed by hand, nothing orbits. */
object FlightWorlds {
    /**
     * A body of [radius] (its `SurfaceRadius`) with a barrier of [collision] (`CollisionRadius`), at
     * ([x], [y], [z]). [gravityG] 0 = no pull (Roblox's trip script has none).
     */
    fun body(
        name: String, radius: Double, collision: Double = radius, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0,
        minApproach: Float = 14f, gravityG: Double = 0.0, shadowFactor: Double = 5.0, star: Boolean = false,
        heatRadius: Double = 0.0, massShadowRadius: Double = 0.0, landable: Boolean = !star, landingBlocked: String? = null,
    ): StarSystem.Body = StarSystem.Body(
        name = name, cls = if (star) "Star G" else "Rocky Body",
        type = if (star) StarSystem.BodyType.STAR else StarSystem.BodyType.ROCK,
        radius = radius, parent = -1, seed = 1, isStar = star, collisionRadius = collision,
        minApproachSpeed = minApproach, gravityG = gravityG, landable = landable, landingBlocked = landingBlocked,
        massShadowFactor = shadowFactor, heatRadius = heatRadius, massShadowRadius = massShadowRadius, rot = Quat(),
    ).also { it.pos.set(x, y, z) }

    /** A system of [bodies] (no stations); [edge] the field radius (0 = no field). */
    fun system(vararg bodies: StarSystem.Body, edge: Double = 0.0): StarSystem =
        StarSystem("Test", 1, bodies.toList(), emptyList(), edge, 1.0)

    /** The result of a 1-D trip. */
    class Trip(val ground: Double, val shadow: Double, val peak: Double, val endSpeed: Double) {
        override fun toString() = "ground %.4f s, shadow edge %.4f s, peak %.4f m/s, end %.4f m/s".format(ground, shadow, peak, endSpeed)
    }

    /**
     * The trip script of 02-flight.md 9.4 (`trip.py`) with the real modules: two bodies on a line
     * (A at the origin, B [d] metres along -Z), a VARROW at full tank and full throttle, nose on B
     * (-Z), starting [startAlt] metres above A at rest; until [stopAlt] above B. Times in seconds.
     * [ra]/[rb] are the law radii (the barrier with [FlightLaw.ROBLOX], the surface with the port).
     */
    fun trip(law: FlightLaw, d: Double, ra: Double, rb: Double, startAlt: Double, stopAlt: Double = 0.0, dt: Double = 1.0 / 60.0, maxT: Double = 2000.0): Trip {
        val a = body("A", ra)
        val b = body("B", rb, z = -d)
        val sys = system(a, b)
        val field = GravityField(law)
        val gs = GravityState()
        val thrust = ShipThrust(law)
        val h = ShipHandling(ShipProfiles.VARROW)
        val rot = Quat()
        val pos = DVec3(0.0, 0.0, -(ra + startAlt))
        val vel = DVec3()
        thrust.setThrottle(1.0)
        var t = 0.0
        var peak = 0.0
        var shadow = Double.NaN
        while (true) {
            field.getState(sys, pos, vel, gs)
            thrust.setSpeedLimit(gs.speedLimit, gs.limitZone == LimitZone.SURFACE)
            h.update(dt, vel.length(), 1.0, 0.0)
            thrust.update(dt, rot, vel, h)
            pos.addScaled(vel, dt)
            t += dt
            val v = vel.length()
            peak = maxOf(peak, v)
            val hb = (d + pos.z) - rb
            if (shadow.isNaN() && hb <= rb * FlightConfig.Gravity.MASS_SHADOW_FACTOR) shadow = t
            if (hb <= stopAlt || t > maxT) return Trip(t, shadow, peak, v)
        }
    }

    /** `ceil(1.105 R) + 6`: Roblox's barrier (studs). */
    fun barrier(r: Double): Double = kotlin.math.ceil(r * 1.105) + 6.0
}
