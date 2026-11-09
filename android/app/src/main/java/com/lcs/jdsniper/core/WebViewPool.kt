package com.lcs.jdsniper.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lcs.jdsniper.data.ItemConfig
import org.json.JSONObject

/**
 * 每个 SKU 一个常驻 WebView：T-5min 就加载好页面，T0 只做点击，
 * 避免临场冷启动 2-4 秒直接错过。
 */
class WebViewPool(
    private val ctx: Context,
    private val container: LinearLayout,
    private val bridge: JsBridge,
    private val onLog: (String) -> Unit
) {

    class Entry(val item: ItemConfig, val web: WebView, val title: TextView) {
        var lastState: String = "准备中"
    }

    private val entries = ArrayList<Entry>()
    private var initScript: String = ""

    fun setInitScript(script: String) {
        initScript = script
    }

    fun build(items: List<ItemConfig>, jsCfg: JSONObject) {
        destroy()
        buildInitScript(jsCfg)

        val picked = items.take(MAX_VIEWS)
        if (picked.isEmpty()) {
            onLog("没有启用的商品，先回主界面勾选 SKU")
            return
        }

        for (item in picked) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(ctx).apply {
                text = "${item.alias} · ${item.sku}"
                setTextColor(0xFFB9C4CF.toInt())
                textSize = 11f
                setPadding(dp(8), dp(6), dp(8), dp(2))
            }
            val web = createWebView(item)
            row.addView(title)
            row.addView(
                web,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            )
            container.addView(row)

            val entry = Entry(item, web, title)
            entries.add(entry)
            web.loadUrl(item.url.ifBlank { "https://item.m.jd.com/product/${item.sku}.html" })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(item: ItemConfig): WebView {
        val web = WebView(ctx)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.setBackgroundColor(0xFF101010.toInt())
        web.addJavascriptInterface(bridge, "__LCS_NATIVE")

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                inject(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                inject(view)
                try {
                    CookieManager.getInstance().flush()
                } catch (e: Exception) {
                    // ignore
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("http://") || u.startsWith("https://")) return false
                // openapp.jdmobile / jdpay / tel: 等一律不出 App
                onLog("拦截外部跳转：$u")
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val req = request ?: return
                if (req.isForMainFrame) {
                    onLog("加载失败：${req.url} code=${error?.errorCode}")
                }
            }
        }
        return web
    }

    private fun buildInitScript(jsCfg: JSONObject) {
        val src = JsAssets.autopilot(ctx)
        initScript = ";(function(){try{$src" +
            ";window.__LCS.init(${jsCfg});" +
            "}catch(e){try{window.__LCS_NATIVE.post(JSON.stringify({type:'error',detail:String(e)}))}catch(_){}}})();"
    }

    private fun inject(view: WebView?) {
        view ?: return
        if (initScript.isEmpty()) return
        try {
            view.evaluateJavascript(initScript, null)
        } catch (e: Exception) {
            onLog("注入失败：${e.message}")
        }
    }

    fun evalAll(js: String) {
        for (e in entries) {
            try {
                e.web.evaluateJavascript(js, null)
            } catch (ex: Exception) {
                onLog("${e.item.alias} 执行失败：${ex.message}")
            }
        }
    }

    fun eval(sku: String, js: String) {
        entries.firstOrNull { it.item.sku == sku }?.let {
            try {
                it.web.evaluateJavascript(js, null)
            } catch (ex: Exception) {
                onLog("执行失败：${ex.message}")
            }
        }
    }

    /** 按商品错开执行（staggerPerItemMs），避免 4 个 WebView 同一毫秒抢同一条链路 */
    fun evalEach(jsFor: (Int, ItemConfig) -> String) {
        entries.forEachIndexed { index, e ->
            try {
                e.web.evaluateJavascript(jsFor(index, e.item), null)
            } catch (ex: Exception) {
                onLog("${e.item.alias} 执行失败：${ex.message}")
            }
        }
    }

    fun reloadAll() {
        for (e in entries) {
            try {
                e.web.reload()
            } catch (ex: Exception) {
                // ignore
            }
        }
    }

    fun markState(sku: String, state: String) {
        entries.firstOrNull { it.item.sku == sku }?.let {
            it.lastState = state
            it.title.text = "${it.item.alias} · ${it.item.sku} · $state"
        }
    }

    fun markAll(state: String) {
        for (e in entries) {
            e.lastState = state
            e.title.text = "${e.item.alias} · ${e.item.sku} · $state"
        }
    }

    fun dumpCurrent(): String? {
        var out: String? = null
        for (e in entries) {
            try {
                e.web.evaluateJavascript(
                    "(window.__LCS?__LCS.probe():'{\"stage\":\"NO_JS\"}')"
                ) { value ->
                    if (out == null) out = value
                }
            } catch (ex: Exception) {
                // ignore
            }
        }
        return out
    }

    fun visibility(on: Boolean) {
        container.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun destroy() {
        for (e in entries) {
            try {
                (e.web.parent as? ViewGroup)?.removeView(e.web)
                e.web.stopLoading()
                e.web.loadUrl("about:blank")
                e.web.destroy()
            } catch (ex: Exception) {
                // ignore
            }
        }
        entries.clear()
        container.removeAllViews()
    }

    fun count(): Int = entries.size

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val MAX_VIEWS = 4
    }
}

object JsAssets {
    @Volatile
    private var cached: String? = null

    fun autopilot(ctx: Context): String {
        cached?.let { return it }
        val s = try {
            ctx.assets.open("autopilot.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        cached = s
        return s
    }
}
