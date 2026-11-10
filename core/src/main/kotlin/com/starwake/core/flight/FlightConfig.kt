package com.starwake.core.flight

import com.starwake.core.world.WorldScale

/**
 * The flight sections of Roblox's `ShipCore/ShipConfig.lua` (02-flight.md section 4), verbatim:
 * Rotation, Cursor, Thrust, Handling, Gravity, Physics, Autopilot, Systems and the parts of Hull,
 * Stations, Ship and Surface the flight uses. Units as in Roblox (1 stud = 1 m): m, m/s, m/s²,
 * seconds; config angles in degrees.
 *
 * Every number here is absolute or a ratio. The far-field speeds that scale with the world (02 9.4:
 * the shadow ceiling, the mass-shadow floor, the star's shadow floor, the far approach brake) and
 * the cruise cap are not constants but members of [FlightLaw], which applies S in one place.
 */
object FlightConfig {
    /** `ShipConfig.Rotation` (`:15`). */
    object Rotation {
        /** Power applied after the dead zone; > 1 is finer in the middle. */
        const val INPUT_CURVE = 1.7f
    }

    /** `ShipConfig.Cursor` (`:1605-1671`): the virtual stick and the flight arrow. */
    object Cursor {
        /** Radial dead zone, a fraction of the cursor radius (also the arrow's idle/colour switch). */
        const val DEADZONE = 0.20f
        const val DOT_SIZE = 4f
        const val ARROW_LENGTH = 20f
        const val ARROW_WIDTH = 17f
        const val ARROW_INNER = 16f
        const val ARROW_RANGE = 112f

        /** Below this deflection only the dot shows. */
        const val ARROW_HIDE = 0.04f
        const val DOT_FADE_RANGE = 0.5f
        const val DOT_DIM_TRANSPARENCY = 0.8f
        const val ARROW_IDLE_TRANSPARENCY = 0.3f

        /** Colours, 0xRRGGBB: the dot, the matte dark red of the dead zone, the idle tint (an image multiplier). */
        const val DOT_COLOR = 0xFFAA2D
        const val ARROW_IDLE = 0x962416
        const val ARROW_IDLE_TINT = 0x962E5E

        /** The two halves of AFloidDEV's HudArrow_TwoTone image: left yellow, right orange. */
        const val ARROW_LEFT = 0xFFC93C
        const val ARROW_RIGHT = 0xF07F1A

        /** Throttle track colours (ShipCursor; reused by the touch lever, 02 6.3). */
        const val FILL_FORWARD = 0xFFAA3C
        const val FILL_REVERSE = 0xC85A3C
        const val SPEED_MARK = 0xFFEBBE
        const val LIMIT_MARK = 0x5AAAFF

        /** 1080-line design pixels (`DESIGN_HEIGHT`). */
        const val DESIGN_HEIGHT = 1080f

        /** Share of the arrow image the triangle fills (184 × 207 of 256²). */
        const val TRIANGLE_WIDTH = 184f / 256f
        const val TRIANGLE_HEIGHT = 207f / 256f

        /** Dot fade steps (`DOT_STEPS`). */
        const val DOT_STEPS = 10
    }

    /** `ShipConfig.Thrust` (`:27-71`). */
    object Thrust {
        /**
         * 1000 m/s, absolute: the throttle map's base, the limit-tracking threshold, the brake's
         * exponential threshold and the HUD/audio reference. The far law's shadow ceiling is the
         * scaled shadow of it ([FlightLaw.shadowCeiling], DECISIONS 2).
         */
        const val MAX_SPEED = 1000.0

        /** Roblox's cruise cap (1 % of c); the port's cap is [FlightLaw.cruiseCap] (0.5 c, DECISIONS 2). */
        const val CRUISE_MAX_SPEED = 3_000_000.0

        /** The gap shrinks by e every this many seconds flying towards a body, station or the edge. */
        const val CRUISE_DISTANCE_TIME = 3.5

        /** The same, flying away. */
        const val CRUISE_DEPART_TIME = 1.5
        const val THROTTLE_RAMP_TIME = 4.0
        const val MIN_THROTTLE = -0.25
        const val FULL_STOP_TIME = 1.2
        const val FLIGHT_ASSIST_DEFAULT = true

        /** Flight Assist zeroes slower residual speed. */
        const val VELOCITY_CUTOFF = 0.05
    }

    /** `ShipConfig.Handling` (`:388-437`). */
    object Handling {
        const val MASS_EXPONENT_ACCELERATION = 0.75
        const val MASS_EXPONENT_TOP_SPEED = 0.15
        const val MASS_EXPONENT_TURN_RATE = 0.2
        const val MASS_EXPONENT_TURN_ACCELERATION = 0.75
        const val MIN_MASS_RATIO = 0.5
        const val BLUE_ZONE_CENTER = 0.5
        const val BLUE_ZONE_LOW = 0.65
        const val BLUE_ZONE_HIGH = 0.7
        const val CRUISE_TIME_UP = 0.8
        const val CRUISE_DOUBLE_TIME = 0.8
        const val CRUISE_TIME_DOWN = 1.8
        const val CRUISE_ALIGN = 0.25
        const val CRUISE_TURN_FLOOR = 0.6
        const val CRUISE_TURN_EXPONENT = 0.6
        const val FA_OFF_TORQUE = 0.7f
        const val FA_OFF_RATE_BONUS = 1.1f
        const val ASSIST_AUTHORITY = 2.0

        /** Felt G: 9.81 m/s² per g (display and blackout). */
        const val FELT_PER_G = 9.81
        const val CRUISE_FELT_FACTOR = 0.1
        const val CRUISE_FELT_MAX_G = 1.5
    }

    /** `ShipConfig.Gravity` (`:448-534`); the far-field speeds are scaled in [FlightLaw]. */
    object Gravity {
        const val MASS_SHADOW_FACTOR = 5.0
        const val MASS_SHADOW_CURVE = 0.75

        /** Far speed: ×S through [FlightLaw.shadowFloor]. */
        const val MASS_SHADOW_FLOOR = 150.0
        const val STAR_SHADOW_FROM_HEAT = 1.0

        /** Far speed: ×S through [FlightLaw.starShadowMinSpeed]. */
        const val STAR_SHADOW_MIN_SPEED = 1000.0
        const val DEFAULT_INFLUENCE_FACTOR = 2.2
        const val DEFAULT_MIN_APPROACH_SPEED = 10.0
        const val LIMIT_MULTIPLIER = 1.0

        /** The gravity brake below MaxSpeed ("should feel like braking, not a wall"). */
        const val BRAKE_ACCELERATION = 80.0
        const val CRUISE_BRAKE_TIME = 0.4
        const val LIMIT_FALL_TIME = 1.6

        /** The approach brake: absolute in the near band, ×S inside the far law ([FlightLaw.approachBrake]). */
        const val APPROACH_BRAKE = 70.0

        /** Seconds to the barrier in the approach zone (`near = h / 2`). */
        const val APPROACH_TIME = 2.0
        const val MIN_MANOEUVRE_SPEED = 12.0
        const val SURFACE_GRAVITY_RANGE = 3.5
        const val GRAVITY_FALLOFF = 2.0

        /** Flight Assist cancels 78 % of the pull. */
        const val FA_GRAVITY_COMPENSATION = 0.78
        const val HEAT_RANGE = 0.6

        /** `DefaultSurfaceGravity` (free-fall-time display only). */
        const val DEFAULT_SURFACE_GRAVITY = 196.2
    }

    /** `ShipConfig.Physics` (`:2433-2458`). */
    object Physics {
        /** Roblox's planet pull per g, m/s² (the port uses 9.81, DECISIONS 5: [FlightLaw.pullPerG]). */
        const val STUDS_PER_G = 60.0
        const val BLACKOUT_START_G = 6.5
        const val BLACKOUT_FULL_G = 11.0
        const val BLACKOUT_MAX_DARKNESS = 0.82
        const val BLACKOUT_RESPONSE = 0.35
        const val G_TOLERANCE_FORWARD = 0.35
        const val G_TOLERANCE_BACKWARD = 0.5
        const val G_TOLERANCE_LATERAL = 0.5
        const val G_TOLERANCE_UP = 1.0
        const val G_TOLERANCE_DOWN = 1.5

        /** Overlay tint when pushing (0xRRGGBB). */
        const val REDOUT_COLOR = 0x6E0000

        /** HUD G colours: >= 6.5 strain, >= 3.9 (0.6 × 6.5), else the panel colour. */
        const val G_DANGER_COLOR = 0xFF463C
        const val G_WARN_COLOR = 0xFFA546
        const val G_WARN_SHARE = 0.6
    }

    /** `ShipConfig.Autopilot` (`:2374-2394`). */
    object Autopilot {
        /** cos ~15°: thrust only when this well aligned. */
        const val ALIGNMENT_FOR_THRUST = 0.965
        const val TURN_GAIN = 4.0
        const val ROLL_TO_PITCH_ANGLE_DEG = 20.0
        const val ROLL_GAIN = 2.0
        const val MAX_CURSOR = 0.95
        const val BRAKE_MARGIN = 1.35
        const val ARRIVE_DISTANCE = 0.35
        const val ARRIVE_SPEED = 25.0
        const val STOP_ALTITUDE = 0.2
        const val DISENGAGE_ON_INPUT = true

        /** Stick share that counts as manual input (also hands control back from the docking computer). */
        const val OVERRIDE_DEFLECTION = 0.5f

        /** Radius of a target without one (`or 100`). */
        const val DEFAULT_TARGET_RADIUS = 100.0
    }

    /** `ShipConfig.Systems` (`:2399-2411`) and the overheat thresholds of `Warnings` (`:571-579`). */
    object Systems {
        const val FUEL_CAPACITY = 100.0
        const val FUEL_PER_SECOND_AT_FULL_THROTTLE = 0.22
        const val FUEL_SCOOP_RATE = 3.5
        const val FUEL_SCOOP_HEAT = 0.075
        const val HEAT_FROM_STAR = 0.115
        const val HEAT_FROM_THRUST = 0.018
        const val HEAT_COOLING = 0.075
        const val HEAT_MAX = 2.2

        /** Scooping while the star's heat exceeds this. */
        const val SCOOP_THRESHOLD = 0.25

        /** Always-on cooling offset (`loss = cooling · (0.4 + heat)`). */
        const val COOLING_OFFSET = 0.4
        const val OVERHEAT_PHASE1 = 0.80
        const val OVERHEAT_PHASE2 = 1.00
        const val OVERHEAT_HYSTERESIS = 0.88

        /** Heat death (`Warnings.Overheat.FatalHeat`). */
        const val FATAL_HEAT = 2.0

        /** Time to impact for the danger colour (`Warnings.Phase1Time`). */
        const val PHASE1_TIME = 10.0

        /** G smoothing (s): G and strain, redout. */
        const val G_SMOOTHING = 0.12
        const val REDOUT_SMOOTHING = 0.25
    }

    /** `ShipConfig.Hull` (`:2617-2623`). */
    object Hull {
        /** Gear down below this height over the ground (m, absolute). */
        const val GEAR_DOWN_ALTITUDE = 60.0
        const val GEAR_LEG_TIME = 1.6f
        const val GEAR_DOOR_TIME = 0.6f
    }

    /** `ShipConfig.Stations` (`:2925-2941`): absolute, at real size (DECISIONS 9). */
    object Stations {
        const val DOCKING_RANGE = 6000.0
        const val MASS_LOCK_SPEED = 260.0
        const val ZONE_RADIUS = 2200.0
        const val ZONE_SPEED = 140.0
        const val HANGAR_SPEED = 60.0

        /** A station hull destroys a ship hitting it faster than this (m/s into the wall). */
        const val CRASH_SPEED = 45.0
    }

    /** `ShipConfig.Ship` (`:2674-2691`): the field edge (its radius scales, these stay). */
    object Ship {
        const val EDGE_SPEED = 150.0
        const val EDGE_MARGIN = 2000.0
    }

    /** `ShipConfig.Surface` (`:744-760`): the landing scene's law, the port's near-ground band (DECISIONS 3). */
    object Surface {
        const val MAX_SPEED = 260.0
        const val MAX_SPEED_HIGH = 4000.0
        const val SPEED_PER_ALTITUDE = 0.35

        /** Below this height over the ground the near law applies: 4000 / 0.35 = 11,429 m. */
        const val NEAR_BAND = MAX_SPEED_HIGH / SPEED_PER_ALTITUDE

        /** The landing scene's gravity damping, everywhere in the port (DECISIONS 5). */
        const val GRAVITY_SCALE = 0.35

        /** A ship hitting visible ground faster than this crashes (m/s, impact). */
        const val SAFE_LANDING_SPEED = 38.0

        /** The attitude indicator shows below this height over an airless body's ground (DECISIONS 15), m. */
        const val ATTITUDE_AIRLESS_ALTITUDE = 50_000.0
    }
}

/**
 * The scale-dependent half of the flight law (02-flight.md 9.4, DECISIONS 2, 3 and 5): which
 * numbers grow with the world scale S and which decisions the port takes over Roblox's numbers.
 *
 * - far-field speeds and accelerations ×S: the shadow ceiling (Roblox `Thrust.MaxSpeed` inside
 *   `getState`), `MassShadowFloor`, `StarShadowMinSpeed`, the far `ApproachBrake`;
 * - [cruiseCap]: Roblox's 3 Mm/s at S = 1, 0.5 c in the port;
 * - gravity: Roblox pulls 60 m/s² per g undamped; the port 9.81 m/s² per g at 35 %;
 * - [nearBand]: the absolute near-ground band below 11.4 km over the surface (DECISIONS 3), and the
 *   body radius the law measures from: Roblox's barrier (`CollisionRadius`) without it, the surface
 *   (the barrier no longer exists) with it;
 * - [shadowApproach]: closing on a body, the far law never allows more than `APPROACH_TIME` (2 s)
 *   to the surface along the closing part, outside the approach zone (2.2 R) too. Roblox's shadow
 *   law (`(h / shadow)^0.75` from the 3 Mm/s ×S ceiling) falls faster than the 0.4 s brake can
 *   follow when the shadow is small, and the approach zone then cuts the limit to a sixth: at
 *   S = 3000 the ship reached Deimos's ground at 90 km/s. Roblox's barrier hid that; the port's
 *   ground cannot (02 9.5). An e-folding law like the cruise law, so the brake follows it; for
 *   bodies whose shadow reaches 6 Mm (2 s at the ceiling) it changes nothing outside the zone.
 *
 * [ROBLOX] reproduces the Roblox game exactly (parity tests); [PORT] is the game at S = 3000.
 */
class FlightLaw(
    val scale: Double,
    val cruiseCap: Double,
    val pullPerG: Double,
    val gravityScale: Double,
    val nearBand: Boolean,
    val shadowApproach: Boolean = nearBand,
) {
    /** The shadow law's ceiling and the cruise base: `Thrust.MaxSpeed` × S. */
    val shadowCeiling = FlightConfig.Thrust.MAX_SPEED * scale
    val shadowFloor = FlightConfig.Gravity.MASS_SHADOW_FLOOR * scale
    val starShadowMinSpeed = FlightConfig.Gravity.STAR_SHADOW_MIN_SPEED * scale

    /** The approach brake inside the far law. */
    val approachBrake = FlightConfig.Gravity.APPROACH_BRAKE * scale

    companion object {
        /** Roblox: S = 1, the 3 Mm/s cap, 60 m/s² per g at full strength, the barrier, no near band. */
        val ROBLOX = FlightLaw(1.0, FlightConfig.Thrust.CRUISE_MAX_SPEED, FlightConfig.Physics.STUDS_PER_G, 1.0, false)

        /** Half the speed of light (DECISIONS 2), m/s. */
        const val HALF_LIGHT = 1.5e8

        /** The port: S = 3000, 0.5 c, 9.81 m/s² per g at 35 % (DECISIONS 5), the near band (DECISIONS 3). */
        val PORT = FlightLaw(WorldScale.S, HALF_LIGHT, 9.81, FlightConfig.Surface.GRAVITY_SCALE, true)
    }
}
