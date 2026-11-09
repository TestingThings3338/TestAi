package com.starwake.core.station

import com.starwake.core.math.DVec3
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a station sits in a star system (ShipStations.offset and ShipStation's placement), and the
 * conversions between world space (doubles, metres) and station space.
 *
 * A station hangs at a fixed offset from its host's REST position (its orbit phase at t = 0, so the
 * offset never turns with the orbit) and is carried along by the host: world position = current
 * host position + offset. It is turned so that its mouth (station -Z) faces the host.
 */
object StationPlacement {
    /**
     * ShipStations.offset: from the host at ([hx], [hy], [hz]) (its rest position) with the star at
     * ([sx], [sy], [sz]), the station's offset for its Angle and Elevation (degrees) at [distance] m
     * (the Roblox Distance already multiplied by the world scale), into [out]. The angle turns the
     * host-to-star direction (flattened onto the XZ plane) about +Y; the elevation lifts it.
     */
    fun offset(
        hx: Double, hy: Double, hz: Double, sx: Double, sy: Double, sz: Double,
        angleDeg: Double, elevationDeg: Double, distance: Double, out: DVec3,
    ): DVec3 {
        var bx = sx - hx
        var bz = sz - hz
        val l = sqrt(bx * bx + bz * bz)
        if (l > 1e-3) {
            bx /= l; bz /= l
        } else {
            bx = 0.0; bz = -1.0
        }
        val a = Math.toRadians(angleDeg)
        // RotY: x' = x cos + z sin, z' = -x sin + z cos
        val dx = bx * cos(a) + bz * sin(a)
        val dz = -bx * sin(a) + bz * cos(a)
        val e = Math.toRadians(elevationDeg)
        return out.set(dx * cos(e) * distance, sin(e) * distance, dz * cos(e) * distance)
    }

    /**
     * The station's rotation for [offset] (host -> station): CFrame.lookAt(0, -offset, up) with up
     * = +Y, or +X when the look is within ~18 degrees of vertical. The mouth (-Z) faces the host.
     */
    fun rotation(ox: Double, oy: Double, oz: Double, out: Quat): Quat {
        val l = sqrt(ox * ox + oy * oy + oz * oz)
        if (l < 1e-9) return out.identity()
        val lx = (-ox / l).toFloat()
        val ly = (-oy / l).toFloat()
        val lz = (-oz / l).toFloat()
        return if (abs(ly) > 0.95f) out.lookRotation(lx, ly, lz, 1f, 0f, 0f) else out.lookRotation(lx, ly, lz, 0f, 1f, 0f)
    }

    /**
     * Camera-relative model matrix of a station at [pos] with [rot] for StationRenderer: station
     * space -> camera-relative world, optionally scaled by [scale] about the eye (a far depth slice).
     */
    fun pose(pos: DVec3, rot: Quat, camera: DVec3, scale: Float, out: Mat4): Mat4 =
        out.setTRS(
            ((pos.x - camera.x) * scale).toFloat(), ((pos.y - camera.y) * scale).toFloat(), ((pos.z - camera.z) * scale).toFloat(),
            rot, scale,
        )

    /** World point ([x], [y], [z]) into the space of a station at [pos] with [rot]. */
    fun toStation(x: Double, y: Double, z: Double, pos: DVec3, rot: Quat, out: Vec3): Vec3 =
        rot.rotateInverse((x - pos.x).toFloat(), (y - pos.y).toFloat(), (z - pos.z).toFloat(), out)

    /** World direction into station space (velocities, normals). */
    fun directionToStation(x: Float, y: Float, z: Float, rot: Quat, out: Vec3): Vec3 = rot.rotateInverse(x, y, z, out)

    /** Station-space point ([x], [y], [z]) to world space. */
    fun toWorld(x: Float, y: Float, z: Float, pos: DVec3, rot: Quat, tmp: Vec3, out: DVec3): DVec3 {
        rot.rotate(x, y, z, tmp)
        return out.set(pos.x + tmp.x, pos.y + tmp.y, pos.z + tmp.z)
    }
}
