package com.starwake.core.game

/**
 * The station-deck session (Roblox ShipDecks, `09` 2.1): while it is active the frame takes the
 * deck branch and the flight rests. Decks are a later phase; until then this holds only the
 * activity flag the frame dispatcher needs, so the branch exists and is tested ([begin]/[end] are
 * the hooks the `09` port and the tests drive).
 */
class DeckSession {
    var active = false
        private set
    private var ending = false

    fun isActive(): Boolean = active

    /** Arrives on a deck (Roblox `decks:arrive`). */
    fun begin() {
        active = true
        ending = false
    }

    /** Leaves the deck at the next [update] (launch): that frame returns early (01 4.9 #1). */
    fun end() {
        if (active) ending = true
    }

    /** Lift, hatch, scenes, NPCs (`09`); here only the pending end. */
    fun update(dt: Float) {
        if (ending) {
            active = false
            ending = false
        }
    }
}

/**
 * The crash cinematic (Roblox ShipDestruction, `05` 2.7): while active the frame takes the
 * destruction branch. The crash flow and the cinematic arrive with `05`; this is the branch's
 * switch and timer ([start] for the port and the tests).
 */
class Destruction {
    var active = false
        private set
    private var timeLeft = 0f

    fun isActive(): Boolean = active

    /** Starts a cinematic of [seconds] (Roblox Destruction.Duration + FadeOutTime = 12.2 s). */
    fun start(seconds: Float) {
        active = true
        timeLeft = seconds
    }

    /** Ends it at once (Roblox `destruction:finish`, called in the black). */
    fun finish() {
        active = false
        timeLeft = 0f
    }

    fun update(dt: Float) {
        if (!active) return
        timeLeft -= dt
        if (timeLeft <= 0f) finish()
    }
}

/**
 * The ship computer's voice (Roblox ShipVoice, `09` 2.13): a queue of lines, said one after the
 * other. Recordings do not exist yet (DECISIONS 31), so a line shows as the HUD message while the
 * message line is free. Fixed capacity, no allocation.
 */
class Voice(private val capacity: Int = 8) {
    private val queue = arrayOfNulls<String>(capacity)
    private var head = 0
    private var size = 0

    /** Lines waiting (tests). */
    val pending: Int get() = size

    /** Queues [line] (dropped when the queue is full). */
    fun say(line: String) {
        if (size == capacity) return
        queue[(head + size) % capacity] = line
        size++
    }

    /** Drops every waiting line (Roblox `voice:interrupt`). */
    fun interrupt() {
        for (i in queue.indices) queue[i] = null
        head = 0
        size = 0
    }

    /** The next line to show when [free] (the message line is empty), else null. */
    fun next(free: Boolean): String? {
        if (!free || size == 0) return null
        val line = queue[head]
        queue[head] = null
        head = (head + 1) % capacity
        size--
        return line
    }

    companion object {
        /** Roblox Voice lines "Welcome" and "SystemsOnline", as HUD text. */
        const val WELCOME = "SHIP COMPUTER ONLINE."
        const val SYSTEMS_ONLINE = "ALL SYSTEMS ONLINE."

        /** Entry heating near the skin's limit. */
        const val HEAT = "HULL TEMPERATURE RISING.  REDUCE SPEED."

        /** Voice.WelcomeDelay: after the reveal (the port has no character to wait for), s. */
        const val WELCOME_DELAY = 5f
    }
}
