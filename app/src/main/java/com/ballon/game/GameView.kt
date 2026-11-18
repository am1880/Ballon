package com.ballon.game

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.ballon.game.core.Game
import com.ballon.game.core.Phase
import kotlin.random.Random

/** Hosts the [Game], drives its frame loop and translates touches into game actions. */
class GameView(context: Context) : View(context), Choreographer.FrameCallback {
    var onQuit: (() -> Unit)? = null

    private val store = BestScoreStore(context)
    private val renderer = Renderer()
    private val state = ViewState()
    private var game: Game? = null
    private var running = false
    private var lastFrameNanos = 0L
    private var prevPhase = Phase.READY

    init {
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            state.insetLeft = i.left.toFloat(); state.insetTop = i.top.toFloat()
            state.insetRight = i.right.toFloat(); state.insetBottom = i.bottom.toFloat()
        } else {
            var l = insets.systemWindowInsetLeft; var t = insets.systemWindowInsetTop
            var r = insets.systemWindowInsetRight; var b = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    l = maxOf(l, it.safeInsetLeft); t = maxOf(t, it.safeInsetTop)
                    r = maxOf(r, it.safeInsetRight); b = maxOf(b, it.safeInsetBottom)
                }
            }
            state.insetLeft = l.toFloat(); state.insetTop = t.toFloat()
            state.insetRight = r.toFloat(); state.insetBottom = b.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        if (game == null) {
            val g = Game(Game.WORLD_WIDTH * h / w, Random.Default, store.load())
            game = g
            state.bestBeforeRun = g.bestScore
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    /** Called from Activity.onPause. */
    fun onHostPause() {
        game?.pause()
        syncKeepScreenOn()
        stopLoop()
    }

    /** Called from Activity.onResume. */
    fun onHostResume() {
        if (isAttachedToWindow) startLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        state.timeSec += dt.coerceIn(0f, 0.1f)
        game?.let { g ->
            g.update(dt)
            if (g.phase == Phase.GAME_OVER && prevPhase != Phase.GAME_OVER) store.save(g.bestScore)
            prevPhase = g.phase
        }
        syncKeepScreenOn()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun syncKeepScreenOn() {
        val on = game?.phase == Phase.PLAYING
        if (keepScreenOn != on) keepScreenOn = on
    }

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        renderer.render(canvas, g, width, height, state)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = game ?: return false
        val scale = width / Game.WORLD_WIDTH
        val wx = event.x / scale
        val wy = event.y / scale
        renderer.layout(g, width, state)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!handleTap(g, wx, wy) && g.phase == Phase.PLAYING) {
                    state.touchX = wx; state.touchY = wy
                    g.steerTo(wx)
                }
            }
            MotionEvent.ACTION_MOVE -> if (g.phase == Phase.PLAYING && !state.touchX.isNaN()) {
                state.touchX = wx; state.touchY = wy
                g.steerTo(wx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                state.touchX = Float.NaN; state.touchY = Float.NaN
            }
        }
        invalidate()
        return true
    }

    /** Returns true when the tap hit a button. */
    private fun handleTap(g: Game, x: Float, y: Float): Boolean {
        when (g.phase) {
            Phase.READY -> if (renderer.startButton.contains(x, y)) { startRun(g); return true }
            Phase.PLAYING -> if (renderer.pauseButton.contains(x, y)) { g.pause(); return true }
            Phase.PAUSED -> if (renderer.resumeButton.contains(x, y)) { g.resume(); return true }
            Phase.GAME_OVER -> when {
                renderer.replayButton.contains(x, y) -> { startRun(g); return true }
                renderer.quitButton.contains(x, y) -> { onQuit?.invoke(); return true }
            }
        }
        return false
    }

    private fun startRun(g: Game) {
        state.bestBeforeRun = g.bestScore
        g.start()
        prevPhase = g.phase
    }
}
