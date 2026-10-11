package com.cck.vrweb

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 啟動測試:確認 App 打開、進入 VR、回到 2D、關閉都不會閃退。
 * GitHub Actions 每次編譯前都會先跑,沒通過就不發佈新版本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    private fun findButton(v: View, text: String): Button? {
        if (v is Button && v.text == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findButton(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test
    fun launchEnterVrAndClose() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val root = c.get().window.decorView
        findButton(root, "VR")!!.performClick()          // 進入 VR
        c.get().onBackPressed()                           // 返回鍵回到 2D
        c.pause().resume()                                // 切到背景再回來
        c.pause().stop().destroy()
    }
}
