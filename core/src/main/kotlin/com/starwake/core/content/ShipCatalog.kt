package com.starwake.core.content

/**
 * The eight ships of the Roblox game, in shipyard order (ShipEconomy.Ships: by price). Name, role
 * and price come from ShipEconomy.Ships, the external camera from ShipConfig.Ships[..].ExternalCamera
 * (ShipConfig.Camera.External when a ship has none). The hulls themselves are in
 * assets/ships/<id>.bin (tools/roblox/convert_ships.py).
 */
object ShipCatalog {
    class Entry(
        val id: String,
        val name: String,
        val role: String,
        /** Price in credits (CR). */
        val price: Int,
        /** Roblox external camera: offset from the ship origin (studs = metres) and pitch (degrees). */
        val cameraX: Float,
        val cameraY: Float,
        val cameraZ: Float,
        val cameraPitchDeg: Float,
    )

    private const val DEFAULT_Y = 16f // ShipConfig.Camera.External: Offset (0, 16, 95), Pitch -8
    private const val DEFAULT_Z = 95f
    private const val DEFAULT_PITCH = -8f

    val all = arrayOf(
        Entry("varrow", "VARROW", "LIGHT MULTIPURPOSE", 22_000, 0f, DEFAULT_Y, DEFAULT_Z, DEFAULT_PITCH),
        Entry("torrin", "TORRIN", "LIGHT COURIER", 26_000, 0f, 13f, 72f, -8f),
        Entry("calder", "CALDER", "FAST MULTIPURPOSE", 31_000, 0f, DEFAULT_Y, DEFAULT_Z, DEFAULT_PITCH),
        Entry("lochran", "LOCHRAN", "LONG-RANGE EXPLORER", 39_000, 0f, 15f, 80f, -8f),
        Entry("brannock", "BRANNOCK", "MEDIUM FREIGHTER", 55_000, 0f, 24f, 140f, -8f),
        Entry("kessock", "KESSOCK", "MINING PROSPECTOR", 95_000, 0f, 26f, 135f, -9f),
        Entry("dornoch", "DORNOCH", "HEAVY MULTIPURPOSE", 140_000, 0f, 24f, 140f, -8f),
        Entry("morven", "MORVEN", "HEAVY FREIGHTER", 265_000, 0f, 36f, 165f, -9f),
    )

    /** Index of the starter ship (VARROW). */
    const val DEFAULT = 0

    fun get(index: Int): Entry = all[index.coerceIn(0, all.size - 1)]
}
