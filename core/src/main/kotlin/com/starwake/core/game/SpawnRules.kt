package com.starwake.core.game

import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.sqrt

/**
 * Where a ship appears (Roblox ShipClient.lua `arrivalPoint`, `clearOfBodies`, `stationSpawn`,
 * the start rules and the crash recovery; 01-core-loop.md 2.1.4, 2.1.5, 4.6). Pure functions over
 * the [StarSystem]; results go into caller-owned vectors.
 *
 * Start (D13): a new pilot starts docked at MERIDIAN (`ShipStations.HomeStation`). Saves (D23:
 * docked at the station the pilot left, or the exact flight pose at rest) arrive with the save
 * manager of `08`.
 */
object SpawnRules {
    /** Warp.ArrivalMargin: the arrival point lies this factor outside the star's danger radius. */
    const val ARRIVAL_MARGIN = 1.15

    /** In front of a station's mouth: this far out along the slot axis (the stand-in's approach point), m. */
    const val STATION_SPAWN_DISTANCE = StationCollision.DOCK_STANDOFF

    /** A parked hull's nose stays this far (m) off the slot's back wall. */
    const val PARK_NOSE_GAP = 5f

    /**
     * Arrival point at a system's star, star at the origin (`:596-602`): outside the heat and the
     * brake zone, `danger = max(heatRadius, massShadowRadius)`, `safe = danger · 1.15`, the point
     * `(0, 0.06·safe, −safe)`. Sol at S = 3000: danger 276,000 km → (0, 19,044 km, −317,400 km).
     */
    fun arrivalPoint(heatRadius: Double, massShadowRadius: Double, out: DVec3): DVec3 {
        val safe = maxOf(heatRadius, massShadowRadius) * ARRIVAL_MARGIN
        return out.set(0.0, 0.06 * safe, -safe)
    }

    /**
     * The arrival pose of [system] "as after a jump": [arrivalPoint] from the star's heat and
     * mass-shadow radii (the builder fills in the Roblox defaults, 1.6 r and 4 r), pushed out of
     * bodies, facing the star.
     */
    fun starArrival(system: StarSystem, outPos: DVec3, outRot: Quat) {
        val star = system.bodies[system.star]
        arrivalPoint(star.heatRadius, star.massShadowRadius, outPos)
        outPos.x += star.pos.x; outPos.y += star.pos.y; outPos.z += star.pos.z
        clearOfBodies(system, outPos)
        lookAt(outPos, star.pos, outRot)
    }

    /**
     * Pushes [p] out of every body it is inside 1.5 radii of, to 3 radii along the same ray (+Y from
     * the very centre); one pass in body order, as Roblox (`:608-620`; a push can land in a body
     * checked earlier, rare). Returns [p].
     */
    fun clearOfBodies(system: StarSystem, p: DVec3): DVec3 {
        for (b in system.bodies) {
            val ax = p.x - b.pos.x
            val ay = p.y - b.pos.y
            val az = p.z - b.pos.z
            val d = sqrt(ax * ax + ay * ay + az * az)
            if (d >= 1.5 * b.radius) continue
            val out = 3.0 * b.radius
            if (d > 1e-3) p.set(b.pos.x + ax / d * out, b.pos.y + ay / d * out, b.pos.z + az / d * out)
            else p.set(b.pos.x, b.pos.y + out, b.pos.z)
        }
        return p
    }

    /** The slot entrance of [st] ([outPos], world) and the outward direction a ship approaches along ([outDir]). */
    fun stationPort(st: StarSystem.Station, outPos: DVec3, outDir: Vec3, tmp: Vec3) {
        StationMesh.dockPort(st.model, tmp, outDir)
        val px = tmp.x
        val py = tmp.y
        val pz = tmp.z
        st.rot.rotate(px, py, pz, tmp)
        outPos.set(st.pos.x + tmp.x, st.pos.y + tmp.y, st.pos.z + tmp.z)
        st.rot.rotate(outDir, tmp)
        outDir.set(tmp).normalize()
    }

    /**
     * In front of [st]'s mouth, looking at the station (Roblox `stationSpawn`: the approach's outer
     * point, `lookAt(outer, root)`): [STATION_SPAWN_DISTANCE] out along the slot axis, level with
     * the station's up.
     */
    fun stationSpawn(st: StarSystem.Station, outPos: DVec3, outRot: Quat) {
        val dir = Vec3()
        val tmp = Vec3()
        stationPort(st, outPos, dir, tmp)
        outPos.set(
            outPos.x + dir.x * STATION_SPAWN_DISTANCE,
            outPos.y + dir.y * STATION_SPAWN_DISTANCE,
            outPos.z + dir.z * STATION_SPAWN_DISTANCE,
        )
        st.rot.rotate(0f, 1f, 0f, tmp)
        outRot.lookRotation(
            (st.pos.x - outPos.x).toFloat(), (st.pos.y - outPos.y).toFloat(), (st.pos.z - outPos.z).toFloat(),
            tmp.x, tmp.y, tmp.z,
        )
    }

    /**
     * How far inside the slot entrance the hull's centre parks: half the tunnel depth, or less so the
     * nose stays [PARK_NOSE_GAP] off the back wall (a hull longer than the tunnel sticks out of it).
     * [scratch] is overwritten (the auto-dock calls this every frame: no allocation).
     */
    fun slotParkDepth(st: StarSystem.Station, spec: ShipSpec, scratch: Vec3 = Vec3()): Double {
        val size = StationMesh.slotSize(st.model, scratch)
        return minOf(size.z * 0.5f, size.z - spec.sizeZ * 0.5f - PARK_NOSE_GAP).toDouble()
    }

    /**
     * Parked in [st]'s slot (the start docked, D13; the end of the auto-dock): the hull's centre on
     * the slot axis [slotParkDepth] inside, nose in, rolled to the station's up. Writes the ship
     * origin into [outPos] (the origin is the flight model's pivot, not the hull's centre).
     */
    fun dockedPose(st: StarSystem.Station, spec: ShipSpec, outPos: DVec3, outRot: Quat) {
        val dir = Vec3()
        val tmp = Vec3()
        stationPort(st, outPos, dir, tmp)
        val depth = slotParkDepth(st, spec)
        st.rot.rotate(0f, 1f, 0f, tmp)
        outRot.lookRotation(-dir.x, -dir.y, -dir.z, tmp.x, tmp.y, tmp.z)
        outRot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
        outPos.set(outPos.x - dir.x * depth - tmp.x, outPos.y - dir.y * depth - tmp.y, outPos.z - dir.z * depth - tmp.z)
    }

    /** The station a new pilot starts docked at (D13: MERIDIAN), the first station when a system has none of that id. */
    fun startStation(system: StarSystem): Int = system.stationIndex(HOME_STATION).coerceAtLeast(0)

    /** Index of the station nearest to [p] (crash recovery, `nearestStation`), or -1. */
    fun nearestStation(system: StarSystem, p: DVec3): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in system.stations.indices) {
            val s = system.stations[i].pos
            val dx = s.x - p.x
            val dy = s.y - p.y
            val dz = s.z - p.z
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * Crash recovery (`respawnAfterCrash`): in front of the station nearest to the crash point
     * [crashPos], else at the star's arrival point. Returns the station used, or -1.
     */
    fun recoveryPose(system: StarSystem, crashPos: DVec3, outPos: DVec3, outRot: Quat): Int {
        val i = nearestStation(system, crashPos)
        if (i < 0) starArrival(system, outPos, outRot) else stationSpawn(system.stations[i], outPos, outRot)
        return i
    }

    /** [out] = looking from [from] at [to], up as close to world +Y as possible (Roblox `CFrame.lookAt`). */
    fun lookAt(from: DVec3, to: DVec3, out: Quat): Quat =
        out.lookRotation((to.x - from.x).toFloat(), (to.y - from.y).toFloat(), (to.z - from.z).toFloat(), 0f, 1f, 0f)

    /** Catalog id of the home station (`ShipStations.HomeStation`). */
    const val HOME_STATION = com.starwake.core.world.StationDirectory.HOME_STATION
}
