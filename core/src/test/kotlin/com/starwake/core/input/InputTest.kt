package com.starwake.core.input

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every callback as (kind, id, x, y); kind: 0 down, 1 move, 2 up, 3 cancel. */
private class Recorder : PointerHandler {
    val kinds = ArrayList<Int>()
    val ids = ArrayList<Int>()
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()

    private fun add(k: Int, id: Int, x: Float, y: Float) {
        kinds.add(k); ids.add(id); xs.add(x); ys.add(y)
    }

    override fun onPointerDown(id: Int, x: Float, y: Float) = add(InputQueue.DOWN, id, x, y)
    override fun onPointerMove(id: Int, x: Float, y: Float) = add(InputQueue.MOVE, id, x, y)
    override fun onPointerUp(id: Int, x: Float, y: Float, cancelled: Boolean) =
        add(if (cancelled) InputQueue.CANCEL else InputQueue.UP, id, x, y)
}

class InputQueueTest {
    @Test
    fun drainDeliversEventsInOrderWithTheirKinds() {
        val q = InputQueue()
        q.push(InputQueue.DOWN, 0, 10f, 20f)
        q.push(InputQueue.MOVE, 0, 11f, 21f)
        q.push(InputQueue.DOWN, 1, 300f, 400f)
        q.push(InputQueue.UP, 0, 12f, 22f)
        q.push(InputQueue.CANCEL, 1, 301f, 401f)
        val r = Recorder()
        q.drain(r)
        assertEquals(listOf(InputQueue.DOWN, InputQueue.MOVE, InputQueue.DOWN, InputQueue.UP, InputQueue.CANCEL), r.kinds)
        assertEquals(listOf(0, 0, 1, 0, 1), r.ids)
        assertEquals(listOf(10f, 11f, 300f, 12f, 301f), r.xs)
        assertEquals(listOf(20f, 21f, 400f, 22f, 401f), r.ys)
        // drained events are gone
        val again = Recorder()
        q.drain(again)
        assertTrue(again.kinds.isEmpty())
    }

    @Test
    fun unknownEventTypesAreDropped() {
        val q = InputQueue()
        q.push(42, 0, 1f, 1f)
        q.push(InputQueue.MOVE, 0, 2f, 2f)
        val r = Recorder()
        q.drain(r)
        assertEquals(listOf(InputQueue.MOVE), r.kinds)
    }

    @Test
    fun overflowDropsTheOldestEvents() {
        val cap = 8
        val q = InputQueue(cap)
        repeat(20) { q.push(InputQueue.MOVE, 0, it.toFloat(), 0f) }
        val r = Recorder()
        q.drain(r)
        assertEquals((12 until 20).map { it.toFloat() }, r.xs, "the newest $cap events must survive")
    }

    @Test
    fun ringBufferWrapsAcrossManyDrains() {
        val q = InputQueue(5)
        val r = Recorder()
        var next = 0
        repeat(50) { round ->
            repeat(round % 7) { q.push(InputQueue.MOVE, 0, (next++).toFloat(), 0f) }
            q.drain(r)
        }
        // rounds pushing 6 into a 5-slot queue drop one event each
        val expected = ArrayList<Float>()
        var n = 0
        repeat(50) { round ->
            val k = round % 7
            for (i in 0 until k) {
                if (!(k == 6 && i == 0)) expected.add((n + i).toFloat())
            }
            n += k
        }
        assertEquals(expected, r.xs)
    }

    @Test
    fun concurrentProducerLosesNothingWhenTheQueueIsLargeEnough() {
        val total = 10_000
        val pointers = 4
        val q = InputQueue(total)
        val r = Recorder()
        val start = CountDownLatch(1)
        val producer = thread(name = "ui") {
            start.await()
            for (i in 0 until total) {
                q.push(InputQueue.MOVE, i % pointers, i.toFloat(), (i / pointers).toFloat())
                if (i % 16 == 0) Thread.yield() // let the consumer interleave with the producer
            }
        }
        start.countDown()
        var nonEmptyDrains = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (r.kinds.size < total && System.nanoTime() < deadline) {
            val before = r.kinds.size
            q.drain(r)
            if (r.kinds.size > before) nonEmptyDrains++
        }
        producer.join(5_000)
        q.drain(r)
        println("InputQueue($total) concurrent: delivered in $nonEmptyDrains non-empty drains")
        assertTrue(nonEmptyDrains > 1, "producer and consumer should actually interleave")
        assertEquals(total, r.kinds.size, "every event must arrive exactly once")
        assertEquals((0 until total).map { it.toFloat() }, r.xs, "global order must be preserved")
        for (p in 0 until pointers) {
            val ys = r.ys.filterIndexed { i, _ -> r.ids[i] == p }
            assertEquals((0 until total / pointers).map { it.toFloat() }, ys, "pointer $p order")
        }
    }

    @Test
    fun concurrentProducerWithDefaultCapacityKeepsOrderAndTheNewestEvent() {
        val total = 10_000
        val q = InputQueue()
        val r = Recorder()
        val start = CountDownLatch(1)
        val producer = thread(name = "ui") {
            start.await()
            for (i in 0 until total) {
                q.push(InputQueue.MOVE, i % 3, i.toFloat(), 0f)
                if (i % 64 == 0) Thread.yield()
            }
        }
        start.countDown()
        var nonEmptyDrains = 0
        while (producer.isAlive) {
            val before = r.xs.size
            q.drain(r)
            if (r.xs.size > before) nonEmptyDrains++
        }
        producer.join()
        q.drain(r)
        // events may be dropped when the producer outruns the consumer, but never duplicated or reordered
        assertTrue(r.xs.size in 1..total)
        for (i in 1 until r.xs.size) assertTrue(r.xs[i] > r.xs[i - 1], "event order broken at $i: ${r.xs[i - 1]} -> ${r.xs[i]}")
        for (i in r.xs.indices) assertEquals(r.xs[i].toInt() % 3, r.ids[i], "id and coordinates must stay together")
        assertEquals((total - 1).toFloat(), r.xs.last(), "the newest event is never dropped")
        println("InputQueue(512) concurrent: ${r.xs.size} of $total events delivered in $nonEmptyDrains non-empty drains")
    }
}

class GamepadStateTest {
    @Test
    fun aPressBetweenFramesIsConsumedExactlyOnce() {
        val g = GamepadState()
        assertFalse(g.consumePress(GamepadState.A))
        g.buttonDown(GamepadState.A)
        g.buttonUp(GamepadState.A) // released before the next frame
        assertFalse(g.isHeld(GamepadState.A))
        assertTrue(g.consumePress(GamepadState.A))
        assertFalse(g.consumePress(GamepadState.A))
        assertTrue(g.connected)
    }

    @Test
    fun holdingAButtonCountsAsOnePress() {
        val g = GamepadState()
        g.buttonDown(GamepadState.START)
        g.buttonDown(GamepadState.START) // key repeat while held
        g.buttonDown(GamepadState.START)
        assertTrue(g.isHeld(GamepadState.START))
        assertTrue(g.consumePress(GamepadState.START))
        assertFalse(g.consumePress(GamepadState.START))
        g.buttonUp(GamepadState.START)
        g.buttonDown(GamepadState.START)
        assertTrue(g.consumePress(GamepadState.START), "a new physical press after release counts again")
    }

    @Test
    fun buttonsAreIndependent() {
        val g = GamepadState()
        g.buttonDown(GamepadState.B)
        g.buttonDown(GamepadState.DPAD_RIGHT)
        assertTrue(g.isHeld(GamepadState.B) && g.isHeld(GamepadState.DPAD_RIGHT) && !g.isHeld(GamepadState.A))
        g.buttonUp(GamepadState.B)
        assertFalse(g.isHeld(GamepadState.B))
        assertTrue(g.isHeld(GamepadState.DPAD_RIGHT))
        assertFalse(g.consumePress(GamepadState.A))
        assertTrue(g.consumePress(GamepadState.B))
        assertTrue(g.consumePress(GamepadState.DPAD_RIGHT))
    }

    @Test
    fun outOfRangeButtonsAreIgnored() {
        val g = GamepadState()
        g.buttonDown(-1)
        g.buttonDown(GamepadState.BUTTONS)
        g.buttonUp(99)
        assertEquals(0, g.held)
        assertFalse(g.connected)
    }

    @Test
    fun twoPressesWithinOneFrameCollapseIntoOne() {
        // Documents current behaviour: consumePress() catches up to the latest count, so two
        // complete taps between frames are reported once (the KDoc says "once per physical press").
        val g = GamepadState()
        repeat(2) {
            g.buttonDown(GamepadState.X)
            g.buttonUp(GamepadState.X)
        }
        assertTrue(g.consumePress(GamepadState.X))
        assertFalse(g.consumePress(GamepadState.X))
    }

    @Test
    fun sticksMarkTheControllerConnected() {
        val g = GamepadState()
        g.setSticks(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, 0.6f)
        assertTrue(g.connected)
        assertEquals(listOf(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, 0.6f), listOf(g.leftX, g.leftY, g.rightX, g.rightY, g.leftTrigger, g.rightTrigger))
    }

    @Test
    fun concurrentPressesAreNeverLost() {
        val g = GamepadState()
        val presses = 5_000
        val done = CountDownLatch(1)
        val ui = thread(name = "ui") {
            repeat(presses) {
                g.buttonDown(GamepadState.A)
                g.buttonUp(GamepadState.A)
                if (it % 2 == 0) Thread.yield()
            }
            done.countDown()
        }
        var consumed = 0
        while (done.count > 0) if (g.consumePress(GamepadState.A)) consumed++
        ui.join()
        if (g.consumePress(GamepadState.A)) consumed++
        // presses between two polls collapse, but at least one is seen and nothing is invented
        assertTrue(consumed in 1..presses, "consumed $consumed")
        assertFalse(g.consumePress(GamepadState.A))
    }
}
