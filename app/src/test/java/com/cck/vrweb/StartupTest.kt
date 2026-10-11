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
 * 啟動測試:確認 App 打開、各功能畫面切換、進入 VR、回到 2D、關閉都不會閃退。
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
        val a = c.get()
        val root = a.window.decorView
        findButton(root, "網頁瀏覽")!!.performClick()      // 首頁 → 網頁瀏覽
        findButton(root, "VR")!!.performClick()           // 進入 VR
        a.onBackPressed()                                  // 返回鍵回到 2D
        a.onBackPressed()                                  // 再返回 → 首頁
        findButton(root, "空中遊覽")!!.performClick()       // 空中遊覽
        findButton(root, "VR")!!.performClick()
        a.onBackPressed()
        a.onBackPressed()                                  // → 首頁
        findButton(root, "我的影片")!!.performClick()       // 我的影片清單
        a.onBackPressed()
        findButton(root, "⚙ 設定")!!.performClick()        // 設定頁
        a.onBackPressed()
        c.pause().resume()                                 // 切到背景再回來
        c.pause().stop().destroy()
    }
}
