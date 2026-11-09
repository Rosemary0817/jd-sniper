package com.lcs.jdsniper.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lcs.jdsniper.core.Notifier

/** 登录态复用系统 WebView 的 Cookie，之后 SniperActivity 直接用同一份 pt_key/pt_pin。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        status = TextView(this).apply {
            text = "登录后可关闭本页；Cookie 会被抢购台复用"
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        val done = Button(this).apply {
            text = "我已登录，返回"
            setOnClickListener { checkAndFinish() }
        }
        web = WebView(this)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = WebViewClient()
        web.loadUrl("https://plogin.m.jd.com/login/login")

        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun checkAndFinish() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // ignore
        }
        val cookie = CookieManager.getInstance().getCookie("https://m.jd.com") ?: ""
        if (cookie.contains("pt_key") && cookie.contains("pt_pin")) {
            Toast.makeText(this, "登录态已就绪", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "还没检测到 pt_key，可能未登录成功", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // ignore
        }
        try {
            web.stopLoading()
            web.destroy()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
