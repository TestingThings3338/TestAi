package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.sqrt

/**
 * Collision shape and identity of a hull (ship space: origin = the pilot's eye, the flight model's
 * pivot, nose −Z). The flight numbers live in [com.starwake.core.flight.ShipProfile]; the shape
 * comes from the hull: Roblox's collision probes ([fromModel]).
 */
class ShipSpec(
    val name: String,
    val role: String,
    /** Price in credits (CR). */
    val price: Int,
    /** Radius of every collision probe sphere. */
    val collisionRadius: Float = 7.0f,
    /** Centres of the collision probes, ship space (x, y, z per probe). */
    val probes: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Height of the origin above the ground when the ship stands on its landing gear. */
    val groundClearance: Float = collisionRadius,
    /** Size of the hull (x width, y height, z length), metres. */
    val sizeX: Float = 2f * collisionRadius,
    val sizeY: Float = 2f * collisionRadius,
    val sizeZ: Float = 2f * collisionRadius,
) {
    /** Radius around the origin that holds every probe sphere (broad-phase bound). */
    val reach: Float

    /** Centre of the hull, ship space: the first probe sits there. */
    val centreX: Float get() = probes[0]
    val centreY: Float get() = probes[1]
    val centreZ: Float get() = probes[2]

    init {
        require(probes.isNotEmpty() && probes.size % 3 == 0) { "probes are x, y, z triples" }
        var r = 0f
        var i = 0
        while (i < probes.size) {
            r = maxOf(r, sqrt(probes[i] * probes[i] + probes[i + 1] * probes[i + 1] + probes[i + 2] * probes[i + 2]))
            i += 3
        }
        reach = r + collisionRadius
    }

    companion object {
        /** A single 7 m sphere at the origin (the old stand-in hull; tests and the harness' collision views). */
        val STANDARD = ShipSpec("STANDARD", "", 0)

        /**
         * Spec of a Roblox ship: name, role and price from the shipyard, the collision shape from the
         * model like ShipStation.probeFor: spheres of radius max(2, smallest bounds side / 2) at the
         * bounds centre (the first probe, [centreX]) and towards both ends (and both sides when the
         * ship is wide), and the ground clearance RootToGround - SeatOffset.Y.
         */
        fun fromModel(entry: com.starwake.core.content.ShipCatalog.Entry, m: com.starwake.core.content.ShipModel): ShipSpec {
            val sx = m.rbxBounds[0]
            val sy = m.rbxBounds[1]
            val sz = m.rbxBounds[2]
            val radius = maxOf(2f, minOf(sx, minOf(sy, sz)) * 0.5f)
            val cx = m.centreX
            val cy = m.centreY
            val cz = m.centreZ
            val alongZ = maxOf(0f, sz * 0.5f - radius)
            val alongX = maxOf(0f, sx * 0.5f - radius)
            val wide = alongX > radius * 0.5f
            val probes = FloatArray(if (wide) 15 else 9)
            fun put(i: Int, x: Float, y: Float, z: Float) {
                probes[i * 3] = x; probes[i * 3 + 1] = y; probes[i * 3 + 2] = z
            }
            put(0, cx, cy, cz)
            put(1, cx, cy, cz - alongZ)
            put(2, cx, cy, cz + alongZ)
            if (wide) {
                put(3, cx - alongX, cy, cz)
                put(4, cx + alongX, cy, cz)
            }
            val ground = m.rbxBounds[6] - m.eyeY
            return ShipSpec(
                entry.name, entry.role, entry.price,
                collisionRadius = radius, probes = probes, groundClearance = ground,
                sizeX = sx, sizeY = sy, sizeZ = sz,
            )
        }
    }
}

/**
 * The flown ship's pose and motion, shared by the flight modules, the collisions, the cameras and
 * the renderer. The velocity is relative to the ship's reference frame (DECISIONS 6) and double
 * precision (at S = 3000 speeds reach 10⁸ m/s).
 */
class ShipState {
    val pos = DVec3()

    /** m/s, relative to the ship's frame. */
    val vel = DVec3()
    val rot = Quat()

    /** Body rates (x pitch, y yaw, z roll), rad/s, Roblox signs ([com.starwake.core.flight.ShipRotation]). */
    val angVel = Vec3()

    /** |vel|, m/s (refreshed by [updateDerived]). */
    var speed = 0.0

    /** Velocity along the nose, m/s (negative in reverse). */
    var forwardSpeed = 0.0

    /** Engine glow 0..1 for the renderer (Roblox: the lever's magnitude). */
    var engineOutput = 0f

    /** The boost burns (the renderer lifts the glow). */
    var boosting = false

    fun reset() {
        pos.set(0.0, 0.0, 0.0)
        vel.zero()
        rot.identity()
        angVel.zero()
        speed = 0.0
        forwardSpeed = 0.0
        engineOutput = 0f
        boosting = false
    }

    /** Refreshes [speed] and [forwardSpeed] from the velocity and the rotation. */
    fun updateDerived() {
        speed = vel.length()
        // the nose: rot · (0, 0, -1)
        val x = rot.x.toDouble()
        val y = rot.y.toDouble()
        val z = rot.z.toDouble()
        val w = rot.w.toDouble()
        val nx = -2.0 * (x * z + w * y)
        val ny = -2.0 * (y * z - w * x)
        val nz = -(1.0 - 2.0 * (x * x + y * y))
        forwardSpeed = vel.x * nx + vel.y * ny + vel.z * nz
    }
}

/**
 * Contacts of the hull's probe spheres ([ShipSpec.probes]) with spheres (bodies, a giant's cloud
 * tops), and the Roblox contact response: the ship slides (`cancelVelocityAlong(-n)`: only the
 * velocity into the surface goes) and the closing speed decides about a crash (02 5.7, `04` 4.4).
 * Double precision throughout: planet radii are millions of metres. No allocation.
 */
class HullContact {
    /** Largest closing speed into a surface of the last [sphere] call, m/s (0 = no contact). */
    var impact = 0.0
        private set

    /** Unit normal (out of the surface) of the contact with the largest [impact]. */
    var nx = 0.0
        private set
    var ny = 0.0
        private set
    var nz = 0.0
        private set

    private val probe = Vec3()

    /**
     * Pushes [ship]'s probes ([spec]) out of the sphere at ([cx], [cy], [cz]) of [radius] and slides
     * it; returns true on contact ([impact], the normal).
     */
    fun sphere(ship: ShipState, spec: ShipSpec, cx: Double, cy: Double, cz: Double, radius: Double): Boolean {
        val p = spec.probes
        val minDist = radius + spec.collisionRadius
        impact = 0.0
        var hit = false
        var i = 0
        while (i < p.size) {
            ship.rot.rotate(p[i], p[i + 1], p[i + 2], probe)
            i += 3
            val dx = ship.pos.x + probe.x - cx
            val dy = ship.pos.y + probe.y - cy
            val dz = ship.pos.z + probe.z - cz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= minDist * minDist) continue
            val d = sqrt(d2)
            // exactly at the centre there is no direction to push along: pick "up"
            val ux = if (d > 1e-4) dx / d else 0.0
            val uy = if (d > 1e-4) dy / d else 1.0
            val uz = if (d > 1e-4) dz / d else 0.0
            val closing = slide(ship, ux, uy, uz, minDist - d)
            if (!hit || closing > impact) {
                impact = closing
                nx = ux; ny = uy; nz = uz
            }
            hit = true
        }
        return hit
    }

    /** Gap (m) between the nearest probe sphere of [ship] and a sphere; negative while touching. */
    fun clearance(ship: ShipState, spec: ShipSpec, cx: Double, cy: Double, cz: Double, radius: Double): Double {
        val p = spec.probes
        var best = Double.MAX_VALUE
        var i = 0
        while (i < p.size) {
            ship.rot.rotate(p[i], p[i + 1], p[i + 2], probe)
            i += 3
            val dx = ship.pos.x + probe.x - cx
            val dy = ship.pos.y + probe.y - cy
            val dz = ship.pos.z + probe.z - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < best) best = d
        }
        return best - radius - spec.collisionRadius
    }

    companion object {
        /**
         * The Roblox contact response: moves [ship] [push] metres out along the unit normal (nx, ny,
         * nz) and removes the velocity into the surface (slide, no bounce). Returns the closing
         * speed it removed (m/s, 0 when separating).
         */
        fun slide(ship: ShipState, nx: Double, ny: Double, nz: Double, push: Double): Double {
            if (push > 0.0) {
                ship.pos.x += nx * push
                ship.pos.y += ny * push
                ship.pos.z += nz * push
            }
            val vn = ship.vel.x * nx + ship.vel.y * ny + ship.vel.z * nz
            if (vn >= 0.0) return 0.0
            ship.vel.x -= nx * vn
            ship.vel.y -= ny * vn
            ship.vel.z -= nz * vn
            return -vn
        }
    }
}
