package com.ballon.game

import android.graphics.Bitmap
import android.graphics.Canvas
import com.ballon.game.core.BarSize
import com.ballon.game.core.Game
import com.ballon.game.core.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val w = 1080
    private val h = 2340
    private val worldH = Game.WORLD_WIDTH * h / w

    private fun newGame(best: Int = 0) = Game(worldH, Random(1), best)

    private fun simulate(g: Game, seconds: Float) {
        g.godMode = true
        var t = 0f
        while (t < seconds) {
            g.steerTo(50f + 30f * Math.sin(t * 1.3).toFloat())
            g.update(1f / 60f); t += 1f / 60f
        }
    }

    private fun shot(name: String, g: Game, touch: Boolean = false, time: Float = 1f, best: Int = 0): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val state = ViewState().apply {
            timeSec = time; insetTop = 90f; bestBeforeRun = best
            if (touch) { touchX = g.balloonX + 10f; touchY = g.balloonY + 20f }
        }
        Renderer().render(Canvas(bmp), g, w, h, state)
        val dir = File("build/screenshots").apply { mkdirs() }
        val f = File(dir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 5_000)
        return f
    }

    @Test
    fun ready() {
        shot("1-ready", newGame(best = 120))
    }

    @Test
    fun playing() {
        val g = newGame(best = 120)
        g.start(); simulate(g, 5f)
        assertEquals(Phase.PLAYING, g.phase)
        shot("2-playing", g, touch = true, time = 3.3f)
    }

    @Test
    fun nightWithMagnet() {
        val g = newGame(best = 300)
        g.start()
        var guard = 0
        while (g.distance < Renderer.SKY_PERIOD * 0.7f && guard++ < 100_000) simulate(g, 0.5f)
        g.addMagnet(g.balloonX, g.balloonY - 3f)
        simulate(g, 0.1f)
        assertTrue(g.magnetTimeLeft > 0f)
        g.addMagnet(30f, g.balloonY - 50f)
        shot("3-night-magnet", g, touch = true, time = 2.1f, best = 300)
    }

    @Test
    fun sunsetAndDusk() {
        for ((n, f) in listOf("5-sunset" to 0.28f, "6-dusk" to 0.45f)) {
            val g = newGame()
            g.start()
            var guard = 0
            while (g.distance < Renderer.SKY_PERIOD * f && guard++ < 100_000) simulate(g, 0.5f)
            shot(n, g)
        }
    }

    @Test
    fun gameOverNewBest() {
        val g = newGame(best = 0)
        g.start(); simulate(g, 5f)
        g.godMode = false
        g.addBar(g.balloonX - 10f, g.balloonY - 1f, BarSize.LONG)
        g.update(0.016f)
        assertEquals(Phase.GAME_OVER, g.phase)
        shot("4-gameover", g, best = 0)
    }
}
