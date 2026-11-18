package com.ballon.game

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BestScoreStoreTest {
    @Test
    fun roundTrip() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = BestScoreStore(ctx)
        assertEquals(0, store.load())
        store.save(137)
        assertEquals(137, BestScoreStore(ctx).load())
        assertEquals(137, ctx.getSharedPreferences("ballon", 0).getInt("best", -1))
    }
}
