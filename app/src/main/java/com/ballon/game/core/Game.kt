package com.ballon.game.core

import kotlin.random.Random

/**
 * Pure-Kotlin rules engine. World is 100 units wide; y grows downward.
 * The balloon stays at a fixed y while the world scrolls down past it.
 */
class Game(val worldHeight: Float, private val random: Random, bestScore: Int = 0) {
    val worldWidth: Float = WORLD_WIDTH
    var phase: Phase = Phase.READY
        private set
    var score: Int = 0
        private set
    var bestScore: Int = bestScore
        private set
    var balloonX: Float = WORLD_WIDTH / 2f
        private set
    val balloonY: Float = worldHeight * 0.6f
    var balloonTilt: Float = 0f
        private set
    var magnetTimeLeft: Float = 0f
        private set
    var distance: Float = 0f
        private set

    private val _bubbles = ArrayList<Bubble>()
    private val _bars = ArrayList<SpikeBar>()
    private val _magnets = ArrayList<Magnet>()
    val bubbles: List<Bubble> get() = _bubbles
    val bars: List<SpikeBar> get() = _bars
    val magnets: List<Magnet> get() = _magnets

    val scrollSpeed: Float get() = minOf(MAX_SCROLL, BASE_SCROLL + SCROLL_RAMP * secondsPlayed)

    /** Test hook: when true, bar collisions do not end the run. */
    internal var godMode = false

    private var secondsPlayed = 0f
    private var targetX = balloonX
    private var rowCursorY = 0f
    private var rowsSinceBar = 0
    private var nextMagnetDistance = MAGNET_INTERVAL

    init {
        resetWorld()
    }

    fun start() {
        if (phase != Phase.READY && phase != Phase.GAME_OVER) return
        if (phase == Phase.GAME_OVER) resetWorld()
        phase = Phase.PLAYING
    }

    fun steerTo(x: Float) {
        if (phase != Phase.PLAYING) return
        targetX = x.coerceIn(BALLOON_MIN_X, BALLOON_MAX_X)
    }

    fun pause() {
        if (phase == Phase.PLAYING) phase = Phase.PAUSED
    }

    fun resume() {
        if (phase == Phase.PAUSED) phase = Phase.PLAYING
    }

    fun update(dt: Float) {
        if (phase != Phase.PLAYING) return
        val step = dt.coerceIn(0f, MAX_DT)
        balloonX += (targetX - balloonX) * minOf(1f, STEER_RATE * step)
        balloonX = balloonX.coerceIn(BALLOON_MIN_X, BALLOON_MAX_X)

        val dy = scrollSpeed * step
        distance += dy
        secondsPlayed += step
        _bubbles.forEach { it.y += dy }
        _bars.forEach { it.y += dy }
        _magnets.forEach { it.y += dy }
        rowCursorY += dy

        if (magnetTimeLeft > 0f) attractBubbles(step)
        magnetTimeLeft = maxOf(0f, magnetTimeLeft - step)

        spawnRows()
        collectBubbles()
        collectMagnets()
        if (!godMode && _bars.any { barHitsBalloon(it) }) {
            crash()
            return
        }
        scoreBarsPassed()
        removeOffscreen()
    }

    // ---- internal test helpers ----

    internal fun clearObjects() {
        _bubbles.clear(); _bars.clear(); _magnets.clear()
    }

    internal fun addBubble(x: Float, y: Float) = Bubble(x, y).also { _bubbles += it }
    internal fun addBar(x: Float, y: Float, size: BarSize) = SpikeBar(x, y, size).also { _bars += it }
    internal fun addMagnet(x: Float, y: Float) = Magnet(x, y).also { _magnets += it }

    // ---- run state ----

    private fun resetWorld() {
        score = 0
        balloonX = WORLD_WIDTH / 2f
        targetX = balloonX
        balloonTilt = 0f
        magnetTimeLeft = 0f
        distance = 0f
        secondsPlayed = 0f
        rowsSinceBar = 2
        nextMagnetDistance = MAGNET_INTERVAL
        clearObjects()
        rowCursorY = balloonY - START_CLEARANCE_ROW
        spawnRows()
    }

    private fun crash() {
        phase = Phase.GAME_OVER
        balloonTilt = CRASH_TILT
        bestScore = maxOf(bestScore, score)
    }

    // ---- spawning ----

    private fun spawnRows() {
        while (rowCursorY > -SPAWN_MARGIN) {
            spawnRow(rowCursorY)
            rowCursorY -= ROW_SPACING
        }
    }

    private fun spawnRow(y: Float) {
        val barAllowed = rowsSinceBar >= MIN_ROWS_BETWEEN_BARS
        val roll = if (barAllowed) random.nextInt(100) else 0
        val withBar = roll >= 35
        val withBubbles = roll < 35 || roll >= 70
        var bar: SpikeBar? = null
        if (withBar) {
            val size = BarSize.values()[random.nextInt(BarSize.values().size)]
            val x = random.nextFloat() * (WORLD_WIDTH - size.width)
            bar = SpikeBar(x, y - BAR_H / 2f, size)
            _bars += bar
            rowsSinceBar = 0
        } else {
            rowsSinceBar++
        }
        if (withBubbles) spawnBubbles(y, bar)
        if (distance >= nextMagnetDistance && trySpawnMagnet(y)) nextMagnetDistance += MAGNET_INTERVAL
    }

    private fun spawnBubbles(y: Float, bar: SpikeBar?) {
        repeat(if (bar == null) 1 else 10) {
            val formation = makeFormation(y)
            val clear = bar == null || formation.none {
                circleHitsRect(it.x, it.y, BUBBLE_R + 1f, bar.x, bar.y, bar.size.width, BAR_H)
            }
            if (clear) {
                _bubbles += formation
                return
            }
        }
    }

    private fun makeFormation(y: Float): List<Bubble> {
        val kind = random.nextInt(3)
        val n = if (kind == 2) 2 else 3 + random.nextInt(3)
        val (dx, dy) = when (kind) {
            0 -> 6f to 0f      // line
            1 -> 5f to 2.5f    // diagonal
            else -> 8f to 0f   // pair
        }
        val span = dx * (n - 1)
        val dir = if (kind == 1 && random.nextBoolean()) -1f else 1f
        val x0 = BUBBLE_MIN_X + random.nextFloat() * (BUBBLE_MAX_X - BUBBLE_MIN_X - span)
        val y0 = y - dy * (n - 1) / 2f
        return List(n) { i ->
            val k = if (dir > 0) i else n - 1 - i
            Bubble(x0 + dx * i, y0 + dy * k)
        }
    }

    private fun trySpawnMagnet(y: Float): Boolean {
        repeat(20) {
            val x = MAGNET_MIN_X + random.nextFloat() * (MAGNET_MAX_X - MAGNET_MIN_X)
            if (_bars.none { circleHitsRect(x, y, MAGNET_R + 1f, it.x, it.y, it.size.width, BAR_H) }) {
                _magnets += Magnet(x, y)
                return true
            }
        }
        return false
    }

    // ---- interactions ----

    private fun attractBubbles(step: Float) {
        for (b in _bubbles) {
            val dx = balloonX - b.x
            val dy = balloonY - b.y
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d <= MAGNET_RANGE && d > 0f) {
                val move = minOf(MAGNET_PULL * step, d)
                b.x += dx / d * move
                b.y += dy / d * move
            }
        }
    }

    private fun collectBubbles() {
        val it = _bubbles.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (circleTouchesBalloon(b.x, b.y, BUBBLE_R)) {
                it.remove()
                score += BUBBLE_POINTS
            }
        }
    }

    private fun collectMagnets() {
        val it = _magnets.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (circleTouchesBalloon(m.x, m.y, MAGNET_R)) {
                it.remove()
                magnetTimeLeft = MAGNET_DURATION
            }
        }
    }

    private fun scoreBarsPassed() {
        val basketBottom = balloonY + BASKET_TOP + BASKET_H
        for (bar in _bars) {
            if (!bar.passed && bar.y > basketBottom) {
                bar.passed = true
                score += BAR_POINTS
            }
        }
    }

    private fun removeOffscreen() {
        _bubbles.removeAll { it.y - BUBBLE_R > worldHeight }
        _bars.removeAll { it.y > worldHeight }
        _magnets.removeAll { it.y - MAGNET_R > worldHeight }
    }

    // ---- geometry ----

    private fun circleTouchesBalloon(cx: Float, cy: Float, r: Float): Boolean {
        val dx = cx - balloonX
        val dy = cy - balloonY
        val rr = BALLOON_R + r
        if (dx * dx + dy * dy <= rr * rr) return true
        return circleHitsRect(cx, cy, r, balloonX - BASKET_W / 2f, balloonY + BASKET_TOP, BASKET_W, BASKET_H)
    }

    private fun barHitsBalloon(bar: SpikeBar): Boolean {
        val w = bar.size.width
        if (circleHitsRect(balloonX, balloonY, BALLOON_R, bar.x, bar.y, w, BAR_H)) return true
        val bx = balloonX - BASKET_W / 2f
        val by = balloonY + BASKET_TOP
        return bx < bar.x + w && bx + BASKET_W > bar.x && by < bar.y + BAR_H && by + BASKET_H > bar.y
    }

    private fun circleHitsRect(cx: Float, cy: Float, r: Float, rx: Float, ry: Float, rw: Float, rh: Float): Boolean {
        val nx = cx.coerceIn(rx, rx + rw)
        val ny = cy.coerceIn(ry, ry + rh)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy <= r * r
    }

    companion object {
        const val WORLD_WIDTH = 100f
        const val BUBBLE_R = 2f
        const val BAR_H = 3f
        const val MAGNET_R = 3f
        const val BALLOON_R = 4f
        const val BASKET_W = 3f
        const val BASKET_H = 3f
        const val BASKET_TOP = 5.5f // below balloon centre

        const val BALLOON_MIN_X = 4f
        const val BALLOON_MAX_X = 96f
        const val STEER_RATE = 12f
        const val MAX_DT = 0.05f
        const val BASE_SCROLL = 30f
        const val SCROLL_RAMP = 0.6f
        const val MAX_SCROLL = 75f

        const val ROW_SPACING = 14f
        const val MIN_ROWS_BETWEEN_BARS = 2 // 2 * 14 = 28 >= 18 units between bars
        const val SPAWN_MARGIN = 16f        // rows are generated this far above the top edge
        const val START_CLEARANCE_ROW = 42f // first row above the balloon centre; clears the 30 unit zone
        const val BUBBLE_MIN_X = 4f
        const val BUBBLE_MAX_X = 96f
        const val MAGNET_MIN_X = 10f
        const val MAGNET_MAX_X = 90f
        const val MAGNET_INTERVAL = 700f
        const val MAGNET_DURATION = 6f
        const val MAGNET_RANGE = 30f
        const val MAGNET_PULL = 80f

        const val BUBBLE_POINTS = 10
        const val BAR_POINTS = 1
        const val CRASH_TILT = 25f
    }
}
