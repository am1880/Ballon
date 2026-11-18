package com.ballon.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ballon.game.core.Game
import com.ballon.game.core.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Mutable per-frame values that are not part of the rules engine. */
class ViewState {
    /** Finger position in world units; NaN when no finger is down. */
    var touchX = Float.NaN
    var touchY = Float.NaN
    var insetLeft = 0f   // pixels
    var insetTop = 0f
    var insetRight = 0f
    var insetBottom = 0f
    var timeSec = 0f
    /** Best score before the current run started (to decide "NEW BEST!"). */
    var bestBeforeRun = 0
}

/**
 * Draws a [Game] with Canvas primitives. Everything is expressed in world units (100 wide);
 * the canvas is scaled once per frame. Paints, paths and rects are reused.
 */
class Renderer {
    // Button rectangles in world units, valid after [layout].
    val startButton = RectF()
    val pauseButton = RectF()
    val resumeButton = RectF()
    val replayButton = RectF()
    val quitButton = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private val skyPaint = Paint()

    private var safeTop = 0f
    private var safeLeft = 0f
    private var safeRight = 0f
    private var worldH = 0f

    private val starX = FloatArray(70)
    private val starY = FloatArray(70)
    private val starR = FloatArray(70)

    // cloud layers: x fraction, y phase fraction, size
    private val cloudX = arrayOf(floatArrayOf(0.1f, 0.45f, 0.8f, 0.3f, 0.65f), floatArrayOf(0.25f, 0.7f, 0.05f, 0.55f, 0.9f))
    private val cloudY = arrayOf(floatArrayOf(0.05f, 0.25f, 0.45f, 0.65f, 0.85f), floatArrayOf(0.15f, 0.35f, 0.55f, 0.75f, 0.95f))
    private val cloudS = arrayOf(floatArrayOf(0.8f, 1.0f, 0.7f, 0.9f, 0.75f), floatArrayOf(1.2f, 1.0f, 1.4f, 1.1f, 0.9f))

    init {
        val r = Random(7)
        for (i in starX.indices) {
            starX[i] = r.nextFloat(); starY[i] = r.nextFloat(); starR[i] = 0.2f + r.nextFloat() * 0.3f
        }
    }

    /** Computes UI layout for the current world size and insets; cheap, safe to call every frame. */
    fun layout(game: Game, widthPx: Int, state: ViewState) {
        val scale = widthPx / Game.WORLD_WIDTH
        worldH = game.worldHeight
        safeTop = maxOf(state.insetTop / scale, 0f) + 3f
        safeLeft = maxOf(state.insetLeft / scale, 0f) + 4f
        safeRight = maxOf(state.insetRight / scale, 0f) + 4f
        val h = worldH
        pauseButton.set(100f - safeRight - 9f, safeTop, 100f - safeRight, safeTop + 9f)
        startButton.set(30f, h * 0.42f - 6.5f, 70f, h * 0.42f + 6.5f)
        resumeButton.set(30f, h * 0.5f - 6.5f, 70f, h * 0.5f + 6.5f)
        val top = panelTop(game.worldHeight, game.balloonY)
        replayButton.set(27f, top + 63f, 73f, top + 75f)
        quitButton.set(32f, top + 78f, 68f, top + 88f)
    }

    fun render(canvas: Canvas, game: Game, widthPx: Int, heightPx: Int, state: ViewState) {
        layout(game, widthPx, state)
        val scale = widthPx / Game.WORLD_WIDTH
        canvas.save()
        canvas.scale(scale, scale)
        val t = state.timeSec
        val h = game.worldHeight

        val sky = skyAt(game.distance)
        drawSky(canvas, h)
        if (sky.night > 0.01f) drawStars(canvas, h, sky.night, t)
        drawClouds(canvas, game.distance, h, 0)
        drawClouds(canvas, game.distance, h, 1)

        // The title screen keeps the sky clean; obstacles appear once the run starts.
        if (game.phase != Phase.READY) {
            for (m in game.magnets) drawMagnet(canvas, m.x, m.y)
            for (bar in game.bars) drawBar(canvas, bar.x, bar.y, bar.size.width)
            for (b in game.bubbles) drawBubble(canvas, b.x, b.y)
        }
        if (game.magnetTimeLeft > 0f) drawMagnetGlow(canvas, game.balloonX, game.balloonY, game.magnetTimeLeft, t)
        drawBalloon(canvas, game.balloonX, game.balloonY, game.balloonTilt + sin(t * 1.8f) * 2.2f)

        if (game.phase == Phase.PLAYING && !state.touchX.isNaN()) drawTouchRing(canvas, state.touchX, state.touchY)

        when (game.phase) {
            Phase.READY -> { drawHud(canvas, game); drawReady(canvas, game) }
            Phase.PLAYING -> drawHud(canvas, game)
            Phase.PAUSED -> { drawHud(canvas, game); drawPaused(canvas, h) }
            Phase.GAME_OVER -> { drawHud(canvas, game); drawGameOver(canvas, game, h, state.bestBeforeRun) }
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- sky

    private class Sky { var top = 0; var bottom = 0; var night = 0f }
    private val skyOut = Sky()

    private fun skyAt(distance: Float): Sky {
        val f = ((distance / SKY_PERIOD) % 1f + 1f) % 1f
        var i = 0
        while (i < KEY_POS.size - 2 && f >= KEY_POS[i + 1]) i++
        val span = KEY_POS[i + 1] - KEY_POS[i]
        val u = ((f - KEY_POS[i]) / span).coerceIn(0f, 1f)
        skyOut.top = lerpColor(KEY_TOP[i], KEY_TOP[i + 1], u)
        skyOut.bottom = lerpColor(KEY_BOTTOM[i], KEY_BOTTOM[i + 1], u)
        skyOut.night = KEY_NIGHT[i] + (KEY_NIGHT[i + 1] - KEY_NIGHT[i]) * u
        return skyOut
    }

    private var lastBottom = 0

    private fun drawSky(canvas: Canvas, h: Float) {
        val s = skyOut
        lastBottom = s.bottom
        // One small shader object per frame; the sky colours change continuously.
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h, s.top, s.bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 100f, h, skyPaint)
    }

    private fun drawStars(canvas: Canvas, h: Float, night: Float, t: Float) {
        fill.shader = null
        for (i in starX.indices) {
            val a = night * (0.55f + 0.45f * sin(t * 2f + i * 1.7f))
            fill.color = argb((a * 255).toInt(), 255, 250, 225)
            canvas.drawCircle(starX[i] * 100f, starY[i] * h * 0.85f, starR[i], fill)
        }
    }

    private fun drawClouds(canvas: Canvas, distance: Float, h: Float, layer: Int) {
        val speed = if (layer == 0) 0.07f else 0.16f
        val alpha = if (layer == 0) 120 else 205
        val c = lerpColor(0xFFFFFFFF.toInt(), lastBottom, 0.4f)
        fill.color = (c and 0x00FFFFFF) or (alpha shl 24)
        val range = h + 50f
        for (i in 0 until 5) {
            val s = cloudS[layer][i]
            val y = (((cloudY[layer][i] * range + distance * speed) % range) + range) % range - 25f
            val x = cloudX[layer][i] * 100f
            drawCloud(canvas, x, y, s)
        }
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x - 11f * s, y - 2.5f * s, x + 11f * s, y + 3f * s)
        canvas.drawRoundRect(rect, 3f * s, 3f * s, fill)
        canvas.drawCircle(x - 4f * s, y - 3f * s, 4.2f * s, fill)
        canvas.drawCircle(x + 2.5f * s, y - 4.5f * s, 5.4f * s, fill)
        canvas.drawCircle(x + 7f * s, y - 1.5f * s, 3.4f * s, fill)
    }

    // ---------------------------------------------------------------- objects

    private fun drawBubble(canvas: Canvas, x: Float, y: Float) {
        val r = Game.BUBBLE_R
        fill.color = 0x66CFF4FF
        canvas.drawCircle(x, y, r, fill)
        stroke.strokeWidth = 0.35f
        stroke.color = 0xDDFFFFFF.toInt()
        canvas.drawCircle(x, y, r - 0.17f, stroke)
        stroke.strokeWidth = 0.2f
        stroke.color = 0x99FF9AD5.toInt()
        canvas.drawCircle(x, y, r - 0.55f, stroke)
        fill.color = 0xE6FFFFFF.toInt()
        rect.set(x - 1.3f, y - 1.3f, x - 0.2f, y - 0.5f)
        canvas.drawOval(rect, fill)
    }

    private fun drawBar(canvas: Canvas, x: Float, y: Float, w: Float) {
        val beamH = 1.9f
        val outline = 0xFF2A1238.toInt()
        // spikes
        val n = (w / 2.6f).toInt().coerceAtLeast(2)
        val sw = w / n
        path.rewind()
        for (i in 0 until n) {
            val sx = x + i * sw
            path.moveTo(sx + 0.15f, y + beamH - 0.2f)
            path.lineTo(sx + sw - 0.15f, y + beamH - 0.2f)
            path.lineTo(sx + sw / 2f, y + Game.BAR_H)
            path.close()
        }
        fill.color = 0xFFFFE066.toInt()
        canvas.drawPath(path, fill)
        stroke.strokeWidth = 0.35f
        stroke.color = outline
        canvas.drawPath(path, stroke)
        // beam
        rect.set(x, y, x + w, y + beamH)
        fill.color = 0xFFFF3B6B.toInt()
        canvas.drawRoundRect(rect, 0.9f, 0.9f, fill)
        stroke.color = outline
        canvas.drawRoundRect(rect, 0.9f, 0.9f, stroke)
        fill.color = 0x66FFFFFF
        rect.set(x + 0.8f, y + 0.3f, x + w - 0.8f, y + 0.75f)
        canvas.drawRoundRect(rect, 0.2f, 0.2f, fill)
    }

    private fun drawMagnet(canvas: Canvas, x: Float, y: Float) {
        val r = 1.75f
        val cy = y - 0.4f
        val legEnd = y + 2.2f
        rect.set(x - r, cy - r, x + r, cy + r)
        // dark outline
        path.rewind()
        path.arcTo(rect, 180f, 180f, true)
        path.lineTo(x + r, legEnd)
        path.moveTo(x - r, cy)
        path.lineTo(x - r, legEnd)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = 2.1f
        stroke.color = 0xFF2A1238.toInt()
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = 1.5f
        stroke.color = 0xFFE5262D.toInt()
        canvas.drawPath(path, stroke)
        // white tips
        stroke.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(x - r, legEnd - 0.9f, x - r, legEnd, stroke)
        canvas.drawLine(x + r, legEnd - 0.9f, x + r, legEnd, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun drawMagnetGlow(canvas: Canvas, x: Float, y: Float, left: Float, t: Float) {
        val pulse = 0.5f + 0.5f * sin(t * 6f)
        val fade = if (left < 1.5f) left / 1.5f else 1f
        val a = fade
        fill.color = argb((40 * a).toInt(), 255, 80, 90)
        canvas.drawCircle(x, y + 1.5f, 9f + pulse, fill)
        stroke.strokeWidth = 0.7f
        stroke.color = argb((200 * a).toInt(), 255, 110, 110)
        canvas.drawCircle(x, y + 1.5f, 9f + pulse, stroke)
        stroke.strokeWidth = 0.4f
        stroke.color = argb((140 * a).toInt(), 255, 230, 230)
        canvas.drawCircle(x, y + 1.5f, 12.5f - pulse, stroke)
    }

    private fun drawBalloon(canvas: Canvas, x: Float, y: Float, tiltDeg: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(tiltDeg)
        val r = Game.BALLOON_R
        val bTop = Game.BASKET_TOP
        val half = Game.BASKET_W / 2f
        val bBot = bTop + Game.BASKET_H

        // ropes
        stroke.strokeWidth = 0.18f
        stroke.color = 0xFF5A3A22.toInt()
        canvas.drawLine(-3.1f, 2.5f, -half, bTop, stroke)
        canvas.drawLine(3.1f, 2.5f, half, bTop, stroke)
        canvas.drawLine(-1.2f, 3.8f, -0.6f, bTop, stroke)
        canvas.drawLine(1.2f, 3.8f, 0.6f, bTop, stroke)
        // skirt
        path.rewind()
        path.moveTo(-2.4f, 3.2f); path.lineTo(2.4f, 3.2f); path.lineTo(1.6f, 4.7f); path.lineTo(-1.6f, 4.7f); path.close()
        fill.color = 0xFF8E2B1B.toInt()
        canvas.drawPath(path, fill)
        // basket
        rect.set(-half, bTop, half, bBot)
        fill.color = 0xFF9A5B2E.toInt()
        canvas.drawRoundRect(rect, 0.35f, 0.35f, fill)
        stroke.strokeWidth = 0.15f
        stroke.color = 0xFF6B3A19.toInt()
        canvas.drawLine(-half, bTop + 1.1f, half, bTop + 1.1f, stroke)
        canvas.drawLine(-half, bTop + 2.0f, half, bTop + 2.0f, stroke)
        canvas.drawLine(-0.5f, bTop + 0.5f, -0.5f, bBot, stroke)
        canvas.drawLine(0.5f, bTop + 0.5f, 0.5f, bBot, stroke)
        fill.color = 0xFFD39A55.toInt()
        rect.set(-half - 0.15f, bTop - 0.1f, half + 0.15f, bTop + 0.5f)
        canvas.drawRoundRect(rect, 0.25f, 0.25f, fill)

        // envelope: striped sphere
        canvas.save()
        path.rewind()
        path.addCircle(0f, 0f, r, Path.Direction.CW)
        canvas.clipPath(path)
        for (i in 0 until 8) {
            val x0 = -r * cos(i * PI.toFloat() / 8f)
            val x1 = -r * cos((i + 1) * PI.toFloat() / 8f)
            fill.color = STRIPES[i]
            canvas.drawRect(x0, -r, x1, r, fill)
        }
        // soft shading on the lower right, highlight on the upper left
        fill.color = 0x22300A00
        rect.set(-r * 0.2f, -r * 0.3f, r * 1.3f, r * 1.3f)
        canvas.drawOval(rect, fill)
        canvas.restore()
        fill.color = 0x66FFFFFF
        rect.set(-2.7f, -2.9f, -1.0f, -1.2f)
        canvas.drawOval(rect, fill)
        stroke.strokeWidth = 0.3f
        stroke.color = 0xFF8E2B1B.toInt()
        canvas.drawCircle(0f, 0f, r, stroke)
        canvas.restore()
    }

    private fun drawTouchRing(canvas: Canvas, x: Float, y: Float) {
        fill.color = 0x26FFFFFF
        canvas.drawCircle(x, y, 7f, fill)
        stroke.strokeWidth = 0.7f
        stroke.color = 0xAAFFFFFF.toInt()
        canvas.drawCircle(x, y, 7f, stroke)
    }

    // ---------------------------------------------------------------- HUD & screens

    private fun drawHud(canvas: Canvas, game: Game) {
        val baseY = safeTop + 7f
        val scoreStr = game.score.toString()
        drawText(canvas, scoreStr, safeLeft, baseY, 8f, Paint.Align.LEFT, 0xFFFFFFFF.toInt(), 0xF51B2A5C.toInt(), 0.9f)
        text.textSize = 8f
        val w = text.measureText(scoreStr)
        drawText(canvas, "BEST ${maxOf(game.bestScore, game.score)}", safeLeft + w + 3f, baseY, 3.8f,
            Paint.Align.LEFT, 0xFFFFE8A0.toInt(), 0xF51B2A5C.toInt(), 0.55f)
        if (game.phase == Phase.PLAYING || game.phase == Phase.PAUSED) drawPauseIcon(canvas)
    }

    private fun drawPauseIcon(canvas: Canvas) {
        val b = pauseButton
        fill.color = 0x55102040
        canvas.drawRoundRect(b, 3f, 3f, fill)
        stroke.strokeWidth = 0.4f
        stroke.color = 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(b, 3f, 3f, stroke)
        fill.color = 0xFFFFFFFF.toInt()
        val cx = b.centerX()
        val cy = b.centerY()
        rect.set(cx - 2.1f, cy - 2.6f, cx - 0.7f, cy + 2.6f)
        canvas.drawRoundRect(rect, 0.4f, 0.4f, fill)
        rect.set(cx + 0.7f, cy - 2.6f, cx + 2.1f, cy + 2.6f)
        canvas.drawRoundRect(rect, 0.4f, 0.4f, fill)
    }

    private fun drawReady(canvas: Canvas, game: Game) {
        val h = game.worldHeight
        drawText(canvas, "BALLON", 50f, h * 0.22f, 17f, Paint.Align.CENTER, 0xFFFFF4D6.toInt(), 0xFFC23B22.toInt(), 1.6f)
        drawText(canvas, "Drag to steer", 50f, h * 0.22f + 10f, 5f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xF51B2A5C.toInt(), 0.7f)
        drawButton(canvas, startButton, "START", 0xFFFF8A3D.toInt(), 0xFFC4521C.toInt())
    }

    /** Game-over panel top: centred, but kept above the balloon and below the HUD. */
    private fun panelTop(h: Float, balloonY: Float): Float =
        minOf(h * 0.5f - 47f, balloonY - 98f).coerceAtLeast(safeTop + 14f)

    private fun drawDim(canvas: Canvas, h: Float) {
        fill.color = 0x990B1236.toInt()
        canvas.drawRect(0f, 0f, 100f, h, fill)
    }

    private fun drawPaused(canvas: Canvas, h: Float) {
        drawDim(canvas, h)
        drawText(canvas, "PAUSED", 50f, h * 0.5f - 22f, 13f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xFF1B2A5C.toInt(), 1.2f)
        drawButton(canvas, resumeButton, "RESUME", 0xFF3CC48B.toInt(), 0xFF1F8F62.toInt())
    }

    private fun drawGameOver(canvas: Canvas, game: Game, h: Float, bestBefore: Int) {
        drawDim(canvas, h)
        val top = panelTop(h, game.balloonY)
        rect.set(10f, top, 90f, top + 94f)
        fill.color = 0xF51B2A5C.toInt()
        canvas.drawRoundRect(rect, 5f, 5f, fill)
        stroke.strokeWidth = 0.5f
        stroke.color = 0x88FFFFFF.toInt()
        canvas.drawRoundRect(rect, 5f, 5f, stroke)
        drawText(canvas, "GAME OVER", 50f, top + 16f, 11f, Paint.Align.CENTER, 0xFFFF8A7A.toInt(), 0xFF2A1238.toInt(), 1.1f)
        drawText(canvas, "SCORE", 50f, top + 26f, 3.8f, Paint.Align.CENTER, 0xFFB9C6F5.toInt(), 0, 0f)
        drawText(canvas, game.score.toString(), 50f, top + 41f, 16f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xFF2A1238.toInt(), 1.2f)
        drawText(canvas, "BEST ${game.bestScore}", 50f, top + 49f, 4.4f, Paint.Align.CENTER, 0xFFFFE8A0.toInt(), 0, 0f)
        if (game.score > bestBefore && game.score > 0) {
            rect.set(33f, top + 53.5f, 67f, top + 60.5f)
            fill.color = 0xFFFFD23F.toInt()
            canvas.drawRoundRect(rect, 3.5f, 3.5f, fill)
            drawText(canvas, "NEW BEST!", 50f, centeredBaseline(rect.centerY(), 4.4f), 4.4f,
                Paint.Align.CENTER, 0xFF7A2E00.toInt(), 0, 0f)
        }
        drawButton(canvas, replayButton, "REPLAY", 0xFFFF8A3D.toInt(), 0xFFC4521C.toInt())
        drawButton(canvas, quitButton, "QUIT", 0xFF5B7CFA.toInt(), 0xFF3249B8.toInt(), 4.6f)
    }

    private fun drawButton(canvas: Canvas, b: RectF, label: String, color: Int, lip: Int, size: Float = 6f) {
        val rad = b.height() / 2f
        rect.set(b.left, b.top + 0.9f, b.right, b.bottom + 0.9f)
        fill.color = lip
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.color = color
        canvas.drawRoundRect(b, rad, rad, fill)
        fill.color = 0x40FFFFFF
        rect.set(b.left + rad * 0.6f, b.top + 0.7f, b.right - rad * 0.6f, b.top + b.height() * 0.38f)
        canvas.drawRoundRect(rect, rad * 0.4f, rad * 0.4f, fill)
        drawText(canvas, label, b.centerX(), centeredBaseline(b.centerY(), size), size, Paint.Align.CENTER,
            0xFFFFFFFF.toInt(), 0x66000000, 0.5f)
    }

    private fun centeredBaseline(cy: Float, size: Float): Float {
        text.textSize = size
        val fm = text.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    private fun drawText(
        canvas: Canvas, s: String, x: Float, baseline: Float, size: Float,
        align: Paint.Align, color: Int, outline: Int, outlineW: Float,
    ) {
        text.textSize = size
        text.textAlign = align
        if (outlineW > 0f) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = outlineW * 2f
            text.strokeJoin = Paint.Join.ROUND
            text.color = outline
            canvas.drawText(s, x, baseline + outlineW * 0.5f, text)
        }
        text.style = Paint.Style.FILL
        text.color = color
        canvas.drawText(s, x, baseline, text)
    }

    // ---------------------------------------------------------------- helpers

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    private fun lerpColor(a: Int, b: Int, u: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * u).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** Distance units for one full day-sunset-dusk-night-day loop. */
        const val SKY_PERIOD = 3600f

        private val KEY_POS = floatArrayOf(0f, 0.28f, 0.45f, 0.62f, 0.82f, 1f)
        private val KEY_TOP = intArrayOf(0xFF3E9BEA.toInt(), 0xFF6A5FCB.toInt(), 0xFF3B2F83.toInt(), 0xFF0A0F3A.toInt(), 0xFF0A0F3A.toInt(), 0xFF3E9BEA.toInt())
        private val KEY_BOTTOM = intArrayOf(0xFFBDE8FF.toInt(), 0xFFFFA36B.toInt(), 0xFFD6618A.toInt(), 0xFF2B3A80.toInt(), 0xFF2B3A80.toInt(), 0xFFBDE8FF.toInt())
        private val KEY_NIGHT = floatArrayOf(0f, 0f, 0.2f, 1f, 1f, 0f)

        private val STRIPES = intArrayOf(
            0xFFFF5A36.toInt(), 0xFFFFB02E.toInt(), 0xFFFFF1C9.toInt(), 0xFFFF7A3D.toInt(),
            0xFFFFD23F.toInt(), 0xFFE8433A.toInt(), 0xFFFFB02E.toInt(), 0xFFFFF1C9.toInt(),
        )
    }
}
