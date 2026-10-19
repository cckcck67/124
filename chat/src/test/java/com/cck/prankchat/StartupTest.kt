package com.cck.prankchat

import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 啟動測試:App 打開、收到分享的聊天記錄、返回、關閉都不會閃退。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    @Test
    fun launchShareAndClose() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val a = c.get()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "[LINE] 與小明的聊天記錄\n\n2026/09/29（二）\n18:48\t小明\t晚上吃什麼\n")
        c.newIntent(share)
        @Suppress("DEPRECATION")
        a.onBackPressed()
        c.pause().resume()
        c.pause().stop().destroy()
    }
}
