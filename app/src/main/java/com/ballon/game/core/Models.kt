package com.ballon.game.core

enum class Phase { READY, PLAYING, PAUSED, GAME_OVER }

enum class BarSize(val width: Float) { SHORT(14f), MEDIUM(26f), LONG(40f) }

/** Collectable bubble; (x, y) is the centre, radius [Game.BUBBLE_R]. */
class Bubble(var x: Float, var y: Float)

/** Spike bar; x is the left edge, y the top, height [Game.BAR_H] (spikes included). */
class SpikeBar(val x: Float, var y: Float, val size: BarSize) {
    internal var passed = false
}

/** Magnet pickup; (x, y) is the centre, radius [Game.MAGNET_R]. */
class Magnet(var x: Float, var y: Float)
