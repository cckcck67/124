package com.cck.mealplanner

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 啟動測試:App 打開、返回、切到背景、關閉都不會閃退。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    @Test
    fun launchBackAndClose() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val a = c.get()
        @Suppress("DEPRECATION")
        a.onBackPressed()
        c.pause().resume()
        c.pause().stop().destroy()
    }
}
