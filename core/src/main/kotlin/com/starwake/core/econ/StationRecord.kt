package com.starwake.core.econ

import com.starwake.core.station.OrbitSlot

/** A deposit on a body (ShipResources `{ Id, Grade }`): a resource id and a grade 1 (POOR) .. 5 (EXCEPTIONAL). */
class Deposit(val id: String, val grade: Int) {
    override fun equals(other: Any?): Boolean = other is Deposit && other.id == id && other.grade == grade
    override fun hashCode(): Int = id.hashCode() * 31 + grade
    override fun toString(): String = "$id $grade"
}

/**
 * A player station or build site as the galaxy store keeps it (ShipGalaxy `Stations[Id]`):
 *
 * `{ Id, Name, Owner, OwnerId, Host, Economy, Slot, Angle, Elevation, Distance, Founded, Stage,
 *    Delivered, BuildEnds?, Operational?, OperationalAt?, Storage?, StorageAt? }`
 *
 * Times are epoch seconds; [NONE] stands for Lua's nil. [delivered] counts whole tons per commodity
 * id; [storage] (null until the station is operational) holds fractional tons.
 */
class StationRecord(var id: String) : OrbitSlot {
    var name: String = ""
    var owner: String? = null
    var ownerId: Long = 0L
    var host: String = ""

    /** Economy id ("Extraction", "Refinery", "Service"); null reads as Extraction (the def) with the Service blurb, as in Lua. */
    var economy: String? = null
    var slot: Int = 0
    override var angle: Double = 0.0
    override var elevation: Double = 0.0
    override var distance: Double = 0.0
    var founded: Long = 0L

    /** Current build stage 1..4. */
    var stage: Int = 1
    val delivered = LinkedHashMap<String, Int>()
    var buildEnds: Long = NONE
    var operational: Boolean = false
    var operationalAt: Long = NONE
    var storage: LinkedHashMap<String, Double>? = null
    var storageAt: Long = NONE

    val building: Boolean get() = buildEnds != NONE

    /** Becomes a deep copy of [other] (the UI's probe copy, the deliver probe); returns this. */
    fun copyFrom(other: StationRecord): StationRecord {
        id = other.id
        name = other.name
        owner = other.owner
        ownerId = other.ownerId
        host = other.host
        economy = other.economy
        slot = other.slot
        angle = other.angle
        elevation = other.elevation
        distance = other.distance
        founded = other.founded
        stage = other.stage
        delivered.clear()
        delivered.putAll(other.delivered)
        buildEnds = other.buildEnds
        operational = other.operational
        operationalAt = other.operationalAt
        val s = other.storage
        storage = if (s == null) null else LinkedHashMap(s)
        storageAt = other.storageAt
        return this
    }

    fun copy(): StationRecord = StationRecord(id).copyFrom(this)

    override fun toString(): String = "StationRecord($id $name @ $host stage $stage${if (operational) " operational" else ""})"

    companion object {
        /** Lua nil for a time field. */
        const val NONE = Long.MIN_VALUE
    }
}
