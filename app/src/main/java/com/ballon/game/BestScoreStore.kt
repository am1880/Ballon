package com.ballon.game

import android.content.Context

/** Persists the best score in SharedPreferences ("ballon" / "best"). */
class BestScoreStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ballon", Context.MODE_PRIVATE)

    fun load(): Int = prefs.getInt("best", 0)

    fun save(best: Int) {
        prefs.edit().putInt("best", best).apply()
    }
}
