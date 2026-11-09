package com.lcs.jdsniper.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.lcs.jdsniper.R

/** 登录态复用系统 WebView 的 Cookie，之后 SniperActivity 直接用同一份 pt_key/pt_pin。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        web = findViewById(R.id.webLogin)
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

        findViewById<MaterialButton>(R.id.btnDone).setOnClickListener { checkAndFinish() }
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
            Toast.makeText(this, "还没检测到 pt_key，可能尚未登录成功", Toast.LENGTH_LONG).show()
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
}
