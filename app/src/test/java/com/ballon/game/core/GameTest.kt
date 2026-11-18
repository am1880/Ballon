package com.ballon.game.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private val h = 200f

    private fun newGame(seed: Int = 1, best: Int = 0) = Game(h, Random(seed), best)

    /** A playing game with an empty world, so nothing interferes with a hand-placed scene. */
    private fun playing(seed: Int = 1, best: Int = 0): Game {
        val g = newGame(seed, best)
        g.start()
        g.clearObjects()
        return g
    }

    // ---- phases ----

    @Test fun startsReadyWithPopulatedStaticWorld() {
        val g = newGame()
        assertEquals(Phase.READY, g.phase)
        assertEquals(100f, g.worldWidth, 0f)
        assertEquals(h * 0.6f, g.balloonY, 0f)
        assertEquals(50f, g.balloonX, 0f)
        assertTrue(g.bubbles.isNotEmpty() || g.bars.isNotEmpty())
        val ys = g.bubbles.map { it.y } + g.bars.map { it.y }
        g.update(0.05f)
        assertEquals(ys, g.bubbles.map { it.y } + g.bars.map { it.y })
        assertEquals(0f, g.distance, 0f)
    }

    @Test fun phaseTransitions() {
        val g = newGame()
        g.pause(); assertEquals(Phase.READY, g.phase)
        g.resume(); assertEquals(Phase.READY, g.phase)
        g.start(); assertEquals(Phase.PLAYING, g.phase)
        g.pause(); assertEquals(Phase.PAUSED, g.phase)
        g.resume(); assertEquals(Phase.PLAYING, g.phase)
        g.start(); assertEquals(Phase.PLAYING, g.phase) // start ignored while playing
        g.pause()
        g.start(); assertEquals(Phase.PAUSED, g.phase) // and while paused
    }

    @Test fun pausedDoesNotAdvance() {
        val g = playing()
        g.pause()
        g.steerTo(90f)
        g.update(0.05f)
        assertEquals(0f, g.distance, 0f)
        assertEquals(50f, g.balloonX, 0f)
    }

    // ---- steering ----

    @Test fun steeringEasesTowardTarget() {
        val g = playing()
        g.steerTo(90f)
        g.update(0.05f)
        assertEquals(50f + 40f * 0.6f, g.balloonX, 1e-3f)
    }

    @Test fun steeringClamps() {
        val g = playing()
        g.steerTo(500f)
        repeat(100) { g.update(0.05f); g.clearObjects() }
        assertEquals(96f, g.balloonX, 0.01f)
        g.steerTo(-50f)
        repeat(100) { g.update(0.05f); g.clearObjects() }
        assertEquals(4f, g.balloonX, 0.01f)
    }

    @Test fun steeringIgnoredUnlessPlaying() {
        val g = newGame()
        g.steerTo(90f)
        g.start()
        g.clearObjects()
        g.update(0.05f)
        assertEquals(50f, g.balloonX, 1e-3f)
    }

    // ---- scrolling ----

    @Test fun scrollSpeedRampsAndCaps() {
        val g = playing()
        g.godMode = true
        assertEquals(30f, g.scrollSpeed, 1e-3f)
        repeat(200) { g.update(0.05f) } // 10 s
        assertEquals(36f, g.scrollSpeed, 0.05f)
        repeat(1400) { g.update(0.05f) } // 80 s total
        assertEquals(75f, g.scrollSpeed, 1e-3f)
        repeat(400) { g.update(0.05f) }
        assertEquals(75f, g.scrollSpeed, 1e-3f)
    }

    @Test fun objectsScrollDownAndDistanceAccumulates() {
        val g = playing()
        g.addBubble(10f, 20f)
        g.update(0.05f)
        assertEquals(20f + 30f * 0.05f, g.bubbles.first { it.x == 10f }.y, 1e-3f)
        assertEquals(1.5f, g.distance, 1e-3f)
    }

    @Test fun dtIsClamped() {
        val a = playing(7); val b = playing(7)
        a.update(1f); b.update(0.05f)
        assertEquals(b.distance, a.distance, 1e-6f)
        assertEquals(1.5f, a.distance, 1e-3f)
    }

    @Test fun offscreenObjectsRemoved() {
        val g = playing()
        g.addBubble(10f, h + 1f)
        g.addBar(10f, h + 1f, BarSize.SHORT)
        g.addMagnet(80f, h + 2f)
        g.update(0.05f)
        assertTrue(g.bubbles.none { it.x == 10f })
        assertTrue(g.bars.none { it.x == 10f })
        assertTrue(g.magnets.none { it.x == 80f })
    }

    // ---- bubbles ----

    @Test fun bubbleOnEnvelopeScores() {
        val g = playing()
        g.addBubble(g.balloonX + 5f, g.balloonY)
        g.update(0.01f)
        assertEquals(10, g.score)
        assertTrue(g.bubbles.isEmpty())
    }

    @Test fun bubbleOnBasketScores() {
        val g = playing()
        g.addBubble(g.balloonX, g.balloonY + 8f)
        g.update(0.01f)
        assertEquals(10, g.score)
    }

    @Test fun distantBubbleDoesNotScore() {
        val g = playing()
        g.addBubble(g.balloonX + 20f, g.balloonY)
        g.update(0.01f)
        assertEquals(0, g.score)
        assertEquals(1, g.bubbles.size)
    }

    // ---- bars ----

    @Test fun passedBarScoresOnce() {
        val g = playing()
        g.steerTo(4f); repeat(30) { g.update(0.05f) } // move left, clear spawns
        g.clearObjects()
        g.addBar(70f, g.balloonY + 6f, BarSize.SHORT)
        repeat(10) { g.update(0.05f) }
        assertEquals(Phase.PLAYING, g.phase)
        assertEquals(1, g.score)
        repeat(10) { g.update(0.05f) }
        assertEquals(1, g.score)
    }

    @Test fun barHitCrashes() {
        val g = playing(best = 3)
        g.addBar(g.balloonX - 5f, g.balloonY - 6f, BarSize.MEDIUM)
        g.addBubble(g.balloonX, g.balloonY)
        g.update(0.05f)
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(25f, g.balloonTilt, 0f)
        assertEquals(10, g.score) // bubble taken first
        assertEquals(10, g.bestScore)
        val d = g.distance
        g.update(0.05f)
        assertEquals(d, g.distance, 0f)
    }

    @Test fun barHittingBasketCrashes() {
        val g = playing()
        g.addBar(g.balloonX - 1f, g.balloonY + 5f, BarSize.SHORT)
        g.update(0.01f)
        assertEquals(Phase.GAME_OVER, g.phase)
    }

    @Test fun crashKeepsHigherBestScore() {
        val g = playing(best = 500)
        g.addBar(g.balloonX, g.balloonY, BarSize.SHORT)
        g.update(0.05f)
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(500, g.bestScore)
    }

    @Test fun restartResetsRunKeepsBest() {
        val g = playing()
        g.addBubble(g.balloonX, g.balloonY)
        g.update(0.05f)
        g.addBar(g.balloonX, g.balloonY, BarSize.SHORT)
        g.update(0.05f)
        assertEquals(Phase.GAME_OVER, g.phase)
        g.steerTo(90f)
        g.start()
        assertEquals(Phase.PLAYING, g.phase)
        assertEquals(0, g.score)
        assertEquals(10, g.bestScore)
        assertEquals(0f, g.balloonTilt, 0f)
        assertEquals(0f, g.distance, 0f)
        assertEquals(50f, g.balloonX, 0f)
        assertEquals(0f, g.magnetTimeLeft, 0f)
        assertEquals(30f, g.scrollSpeed, 1e-3f)
        assertTrue(g.bubbles.isNotEmpty() || g.bars.isNotEmpty())
    }

    // ---- magnets ----

    @Test fun magnetPickupActivatesAndExpires() {
        val g = playing()
        g.addMagnet(g.balloonX, g.balloonY)
        g.update(0.05f)
        assertTrue(g.magnets.isEmpty())
        assertEquals(6f, g.magnetTimeLeft, 0.06f)
        repeat(60) { g.update(0.05f); g.clearObjects() }
        assertEquals(3f, g.magnetTimeLeft, 0.1f)
        repeat(65) { g.update(0.05f); g.clearObjects() }
        assertEquals(0f, g.magnetTimeLeft, 0f)
    }

    @Test fun magnetAttractsNearbyBubbles() {
        val g = playing()
        g.addMagnet(g.balloonX, g.balloonY)
        g.update(0.01f)
        g.clearObjects()
        val near = g.addBubble(g.balloonX + 25f, g.balloonY - 5f)
        val far = g.addBubble(g.balloonX + 25f, g.balloonY - 40f)
        val nearStart = near.x; val farStart = far.x
        g.update(0.05f)
        assertTrue(near.x < nearStart - 3f) // 80 * 0.05 = 4 toward balloon
        assertEquals(farStart, far.x, 1e-4f)
    }

    @Test fun bubblesNotAttractedWithoutMagnet() {
        val g = playing()
        val b = g.addBubble(g.balloonX + 25f, g.balloonY - 5f)
        g.update(0.05f)
        assertEquals(g.balloonX + 25f, b.x, 1e-4f)
    }

    @Test fun attractedBubbleIsEventuallyCollected() {
        val g = playing()
        g.addMagnet(g.balloonX, g.balloonY)
        g.update(0.01f)
        g.clearObjects()
        g.addBubble(g.balloonX + 25f, g.balloonY - 5f)
        repeat(20) { g.update(0.05f); if (g.score > 0) return@repeat }
        assertTrue(g.score >= 10)
    }

    // ---- spawning / determinism ----

    private fun assertInvariants(g: Game, label: String) {
        g.bubbles.forEach {
            assertTrue("$label bubble x ${it.x}", it.x >= 4f - 1e-3f && it.x <= 96f + 1e-3f)
        }
        g.bars.forEach {
            assertTrue("$label bar x ${it.x}", it.x >= 0f && it.x + it.size.width <= 100f)
        }
        g.magnets.forEach {
            assertTrue("$label magnet x ${it.x}", it.x >= 10f && it.x <= 90f)
            g.bars.forEach { bar ->
                val cx = it.x.coerceIn(bar.x, bar.x + bar.size.width)
                val cy = it.y.coerceIn(bar.y, bar.y + Game.BAR_H)
                assertTrue("$label magnet on bar", Math.hypot((it.x - cx).toDouble(), (it.y - cy).toDouble()) > Game.MAGNET_R)
            }
        }
        val ys = g.bars.map { it.y }.sorted()
        ys.zipWithNext().forEach { (a, b) -> assertTrue("$label bar spacing ${b - a}", b - a >= 18f) }
    }

    @Test fun spawnInvariantsOver120Seconds() {
        for (seed in listOf(1, 2, 3, 42, 1234)) {
            val g = Game(h, Random(seed))
            g.start()
            g.godMode = true
            val seenMagnets = HashSet<Magnet>()
            var sawBar = false
            var sawBubble = false
            var maxMagnetsOnScreen = 0
            // start-of-run clearance
            (g.bubbles.map { it.y + 2f } + g.bars.map { it.y + Game.BAR_H }).forEach {
                assertTrue("seed $seed clearance", it < g.balloonY - Game.BALLOON_R - 30f + 1e-3f)
            }
            repeat(2400) { step ->
                g.update(0.05f)
                if (step % 7 == 0) g.steerTo((step * 13 % 90 + 5).toFloat())
                assertInvariants(g, "seed $seed step $step")
                seenMagnets += g.magnets
                if (g.bars.isNotEmpty()) sawBar = true
                if (g.bubbles.isNotEmpty()) sawBubble = true
                maxMagnetsOnScreen = maxOf(maxMagnetsOnScreen, g.magnets.size)
            }
            assertTrue(sawBar && sawBubble)
            assertTrue("seed $seed magnets ${seenMagnets.size}", seenMagnets.size >= 2)
            assertTrue(g.distance > 3000f)
        }
    }

    @Test fun spawnedBarsAndBubblesDoNotOverlapWithinInitialWorld() {
        for (seed in 1..30) {
            val g = Game(h, Random(seed))
            g.bubbles.forEach { b ->
                g.bars.forEach { bar ->
                    val cx = b.x.coerceIn(bar.x, bar.x + bar.size.width)
                    val cy = b.y.coerceIn(bar.y, bar.y + Game.BAR_H)
                    assertTrue(Math.hypot((b.x - cx).toDouble(), (b.y - cy).toDouble()) > Game.BUBBLE_R)
                }
            }
        }
    }

    @Test fun sameSeedSameRun() {
        fun run(seed: Int): List<Float> {
            val g = Game(h, Random(seed))
            g.start()
            g.godMode = true
            val out = ArrayList<Float>()
            repeat(600) { i ->
                if (i % 11 == 0) g.steerTo((i * 7 % 90 + 5).toFloat())
                g.update(0.033f)
            }
            out += g.score.toFloat(); out += g.balloonX; out += g.distance
            g.bubbles.forEach { out += it.x; out += it.y }
            g.bars.forEach { out += it.x; out += it.y }
            g.magnets.forEach { out += it.x; out += it.y }
            return out
        }
        assertEquals(run(5), run(5))
        assertNotEquals(run(5), run(6))
    }
}
