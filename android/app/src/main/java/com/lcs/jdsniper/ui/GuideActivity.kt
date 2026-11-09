package com.lcs.jdsniper.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.lcs.jdsniper.R

/** 离线使用指南：从 assets/guide.html 加载，不联网也能看。 */
class GuideActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        val web = findViewById<WebView>(R.id.webGuide)
        web.setBackgroundColor(0xFFF1F2F5.toInt())
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.builtInZoomControls = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.loadUrl("file:///android_asset/guide.html")
    }
}
