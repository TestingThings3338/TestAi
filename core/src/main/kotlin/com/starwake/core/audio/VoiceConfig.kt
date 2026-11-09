package com.starwake.core.audio

/**
 * The announcer's priority tiers (`ShipConfig.Voice.Tiers`, `09` 3.7): higher tiers stand at the
 * front of the queue and expire sooner; WARNING cuts every lower line and is preceded by [hasTone]
 * (the 0.9 s alarm, [SoundBank.WARNING_PHASE1]). CAUTION's tone was never uploaded in Roblox
 * ("Tone_Caution.mp3"), so CAUTION lines play without one.
 */
enum class Tier(val rank: Float, val maxWait: Float, val hasTone: Boolean, val interrupts: Boolean) {
    STATUS(1f, 6f, false, false),
    ADVISORY(2f, 5f, false, false),
    CAUTION(3f, 3f, false, false),
    WARNING(4f, 1.5f, true, true),
}

/**
 * The 92 voice keys of `09` Appendix A.5 (`ShipConfig.Voice.Lines`, `Tier`, `NoTone`, `Timed`), with
 * the spoken text: *src* texts from the Lua, the others proposed by the spec; "DOCKING COMPUTER"
 * speaks as "AUTO-DOCK" (DECISIONS 30). Keys starting with "Control" are station traffic control
 * ([radio]: the Control effect chain), all others the ship computer.
 */
enum class VoiceLine(
    val tier: Tier,
    val text: String,
    /** Voice.NoTone: the situation has its own alarm, or a tone would come too late. */
    val noTone: Boolean = false,
    /** Voice.Timed: the countdown; rank 3.5, expires after 0.5 s, cuts STATUS and ADVISORY. */
    val timed: Boolean = false,
) {
    PullUp(Tier.WARNING, "Pull up."),
    TerrainAhead(Tier.WARNING, "Terrain ahead."),
    Terrain(Tier.WARNING, "Terrain."),
    SinkRate(Tier.WARNING, "Sink rate."),
    ImpactImminent(Tier.WARNING, "Impact imminent.", noTone = true),
    TooLowGear(Tier.CAUTION, "Too low. Gear."),
    TooFastToLand(Tier.CAUTION, "Too fast to land."),
    AltFiveHundred(Tier.ADVISORY, "Five hundred."),
    AltOneHundred(Tier.ADVISORY, "One hundred."),
    AltFifty(Tier.ADVISORY, "Fifty."),
    AltThirty(Tier.ADVISORY, "Thirty."),
    AltTwenty(Tier.ADVISORY, "Twenty."),
    AltTen(Tier.ADVISORY, "Ten."),
    Flare(Tier.CAUTION, "Flare."),
    GearDown(Tier.STATUS, "Gear down."),
    GearUp(Tier.STATUS, "Gear up."),
    Touchdown(Tier.STATUS, "Touchdown."),
    LandingBlocked(Tier.CAUTION, "Landing blocked."),
    MinimumAltitude(Tier.CAUTION, "Minimum altitude."),
    EntryStarted(Tier.STATUS, "Atmospheric entry."),
    EntryHeat(Tier.ADVISORY, "Entry heat building."),
    Turbulence(Tier.ADVISORY, "Turbulence."),
    CloudDeck(Tier.ADVISORY, "Entering cloud deck."),
    LevelOff(Tier.STATUS, "Entry complete. You have control."),
    AscentStarted(Tier.STATUS, "Climbing out."),
    LeavingAtmosphere(Tier.ADVISORY, "Leaving atmosphere."),
    OrbitReached(Tier.STATUS, "Orbit reached."),
    Overspeed(Tier.WARNING, "Overspeed."),
    ApproachingSpeedLimit(Tier.CAUTION, "Approaching speed limit."),
    HighG(Tier.WARNING, "High G."),
    Redout(Tier.WARNING, "Redout."),
    FlightAssistOff(Tier.CAUTION, "Flight assist off."),
    FlightAssistOn(Tier.STATUS, "Flight assist on."),
    BoostReady(Tier.STATUS, "Boost ready."),
    HeatRising(Tier.ADVISORY, "Heat rising."),
    OverheatStage1(Tier.CAUTION, "Overheating.", noTone = true),
    OverheatCritical(Tier.WARNING, "Heat critical.", noTone = true),
    HeatNormal(Tier.STATUS, "Heat normal."),
    GravityEntered(Tier.ADVISORY, "Entering gravity well."),
    GravityLeft(Tier.STATUS, "Leaving gravity well."),
    FuelLow(Tier.CAUTION, "Fuel low."),
    FuelCritical(Tier.WARNING, "Fuel critical."),
    ScoopStarted(Tier.STATUS, "Fuel scooping."),
    TankFull(Tier.STATUS, "Fuel tank full."),
    CollisionAlert(Tier.WARNING, "Collision alert."),
    HullImpact(Tier.WARNING, "Impact.", noTone = true),
    CargoFull(Tier.CAUTION, "Cargo hold full."),
    ScanStarted(Tier.STATUS, "Scan started."),
    ScanComplete(Tier.STATUS, "Scan complete."),
    NewDiscovery(Tier.ADVISORY, "New discovery."),
    DataStored(Tier.STATUS, "Data stored."),
    DepositDetected(Tier.ADVISORY, "Deposit detected."),
    ExtractionComplete(Tier.STATUS, "Extraction complete."),
    HullFailure(Tier.WARNING, "Hull failure.", noTone = true),
    ShipDestroyed(Tier.STATUS, "Ship destroyed."),
    ReplacementFee(Tier.STATUS, "Replacement fee deducted."),
    CargoLost(Tier.STATUS, "Cargo lost."),
    SurveyDataLost(Tier.STATUS, "Survey data lost."),
    ReplacementReady(Tier.STATUS, "Replacement ship ready."),
    Rebooting(Tier.STATUS, "Rebooting."),
    Welcome(Tier.STATUS, "Ship computer online."),
    SystemsOnline(Tier.STATUS, "All systems online."),
    DockingRequestSent(Tier.STATUS, "Docking request sent."),
    DockingComputerEngaged(Tier.STATUS, "Auto-dock engaged."),
    Docked(Tier.STATUS, "Docked."),
    LaunchInitiated(Tier.STATUS, "Launch initiated."),
    DepartureClear(Tier.STATUS, "Departure clear."),
    ControlRequestReceived(Tier.ADVISORY, "Request received. Stand by."),
    ControlDockingGranted(Tier.ADVISORY, "Docking request granted."),
    ControlDockingDenied(Tier.CAUTION, "Docking request denied."),
    ControlHoldPosition(Tier.CAUTION, "Hold position."),
    ControlClearedToLaunch(Tier.ADVISORY, "Cleared to launch."),
    ControlReduceSpeed(Tier.CAUTION, "Reduce speed."),
    AutopilotOn(Tier.STATUS, "Autopilot engaged."),
    AutopilotOff(Tier.STATUS, "Autopilot disengaged."),
    AutopilotNoTarget(Tier.ADVISORY, "No target selected."),
    AutopilotTargetReached(Tier.STATUS, "Target reached."),
    AutopilotManualOverride(Tier.ADVISORY, "Manual override."),
    AutopilotAtmosphere(Tier.CAUTION, "Autopilot off. Atmosphere."),
    CruiseOn(Tier.STATUS, "Cruise engaged."),
    CruiseOff(Tier.STATUS, "Cruise disengaged."),
    CruiseDropped(Tier.CAUTION, "Cruise dropped."),
    JumpCharging(Tier.STATUS, "Jump drive charging."),
    Count5(Tier.STATUS, "Five.", timed = true),
    Count4(Tier.STATUS, "Four.", timed = true),
    Count3(Tier.STATUS, "Three.", timed = true),
    Count2(Tier.STATUS, "Two.", timed = true),
    Count1(Tier.STATUS, "One.", timed = true),
    Jump(Tier.STATUS, "Jump.", timed = true),
    JumpCancelled(Tier.CAUTION, "Jump cancelled."),
    JumpBlocked(Tier.CAUTION, "Jump blocked."),
    JumpArrival(Tier.STATUS, "Jump complete."),
    ;

    /** Station traffic control: the radio chain (keys starting with "Control"). */
    val radio: Boolean = name.startsWith("Control")

    /** The queue rank: 3.5 for the countdown, else the tier's. */
    val rank: Float get() = if (timed) VoiceConfig.TIMED_RANK else tier.rank

    companion object {
        val ALL: Array<VoiceLine> = values()
        val COUNT: Int = ALL.size

        fun of(i: Int): VoiceLine = ALL[i]

        /** The key named [name] (case-insensitive), null when unknown. Debug path. */
        fun parse(name: String): VoiceLine? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** `ShipConfig.Voice` (`09` 3.7) and the notice and comms maps of 2.15. */
object VoiceConfig {
    /** Voice.Volume: x EffectsVolume = the voice group gain (applied live, unlike Roblox). */
    const val VOLUME = 1.6f
    const val GAP = 0.35f
    const val QUEUE_LIMIT = 4
    const val REPEAT_COOLDOWN = 2.5f
    const val MAX_WAIT = 6f
    const val WELCOME_DELAY = 5f
    const val TONE_VOLUME = 0.5f
    const val TONE_GAP = 0.08f
    const val TIMED_MAX_WAIT = 0.5f
    const val TIMED_GAP = 0.08f

    /** ShipVoice TIMED_RANK: the countdown ranks after WARNING and before CAUTION. */
    const val TIMED_RANK = 3.5f

    /** Safety deadline of a line / a tone whose length never becomes known (MAX_UNKNOWN_LINE / _TONE). */
    const val MAX_UNKNOWN_LINE = 8f
    const val MAX_UNKNOWN_TONE = 1.2f

    /** END_SLACK: some air behind the measured end. */
    const val END_SLACK = 0.15f

    /** The WARNING tier's tone (the 0.9 s ImpactClosingAlarm, also Warnings.Sounds.Phase1). */
    const val WARNING_TONE = SoundBank.WARNING_PHASE1

    /**
     * Voice.Effects group volumes: Computer 1, Control 1.3. The Control boost made up for the radio
     * band's lost loudness; the port loudness-normalises every line after its chain (8.4), which does
     * that job, so both voices play at [LINE_GAIN] and the 1.3 stays inside the chain ([VoiceFx]).
     */
    const val COMPUTER_VOLUME = 1f
    const val CONTROL_VOLUME = 1.3f
    const val LINE_GAIN = 1f

    /** Voice.NoticeLines: toast title to lines (ShipNotices.speakNotice). */
    val NOTICE_TITLES = arrayOf(
        "JUMP BLOCKED",
        "MINIMUM ALTITUDE",
        "SURVEY COMPLETE",
        "FIRST DISCOVERY",
        "ALREADY CHARTED",
        "CARGO HOLD FULL",
        "LAUNCH DENIED",
    )
    val NOTICE_LINES: Array<Array<VoiceLine>> = arrayOf(
        arrayOf(VoiceLine.JumpBlocked),
        arrayOf(VoiceLine.MinimumAltitude),
        arrayOf(VoiceLine.ScanComplete, VoiceLine.DataStored),
        arrayOf(VoiceLine.ScanComplete, VoiceLine.NewDiscovery, VoiceLine.DataStored),
        arrayOf(VoiceLine.ScanComplete),
        arrayOf(VoiceLine.CargoFull),
        arrayOf(VoiceLine.ControlHoldPosition),
    )

    /**
     * Voice.CommsLines: a comms body starting with the prefix says the lines; the first match counts.
     * "AUTO-DOCK ENGAGED" is the port's wording of "DOCKING COMPUTER ENGAGED" (DECISIONS 30).
     */
    val COMMS_PREFIXES = arrayOf(
        "DOCKING REQUEST GRANTED",
        "DOCKING REQUEST DENIED",
        "DOCKING DENIED",
        "DOCKING COMPUTER ENGAGED",
        "AUTO-DOCK ENGAGED",
        "LANDING CLAMPS ENGAGED",
        "LAUNCH CLEARED",
        "YOU ARE CLEAR OF THE STATION",
    )
    val COMMS_LINES: Array<Array<VoiceLine>> = arrayOf(
        arrayOf(VoiceLine.ControlDockingGranted),
        arrayOf(VoiceLine.ControlDockingDenied),
        arrayOf(VoiceLine.ControlDockingDenied),
        arrayOf(VoiceLine.DockingComputerEngaged),
        arrayOf(VoiceLine.DockingComputerEngaged),
        arrayOf(VoiceLine.Docked),
        arrayOf(VoiceLine.ControlClearedToLaunch, VoiceLine.LaunchInitiated),
        arrayOf(VoiceLine.DepartureClear),
    )
}
