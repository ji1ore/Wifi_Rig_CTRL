package com.ji1ore.wifi_rig_ctrl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * POTAのハンターログを取得するActivity。
 * WebViewでpota.appにログインし、Authorizationヘッダを傍受してlogbook APIを呼び出す。
 * 取得したJSONをINTENT_RESULT_JSONでResultとして返す。
 */
class PotaHuntLogActivity : AppCompatActivity() {

    companion object {
        const val INTENT_RESULT_JSON = "hunter_json"
    }

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var btnFetch: Button
    private lateinit var progressBar: ProgressBar

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var navigated = false
    private var huntCaptured = false
    private var fetchDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(0xFF000000.toInt())
        }

        tvStatus = TextView(this).apply {
            text = "pota.app にログインしてください"
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(16, 8, 16, 0)
        }

        btnFetch = Button(this).apply {
            text = "ログ取得"
            isEnabled = false
            alpha = 0.5f
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        layout.addView(tvStatus)
        layout.addView(progressBar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        layout.addView(btnFetch, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        layout.addView(webView)
        setContentView(layout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        ServiceWorkerController.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(r: WebResourceRequest) = handleRequest(r)
            }
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                handleRequest(request)

            override fun onPageFinished(view: WebView, url: String) {
                if (fetchDone) return
                val isPota = url.startsWith("https://pota.app") &&
                    !url.contains("auth0") && !url.contains("/login")
                if (isPota && !navigated) {
                    navigated = true
                    runOnUiThread {
                        if (!fetchDone) {
                            tvStatus.text = "ログイン完了。「ログ取得」を押してください"
                            btnFetch.isEnabled = true
                            btnFetch.alpha = 1f
                        }
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }
        }

        btnFetch.setOnClickListener {
            if (!navigated || huntCaptured) return@setOnClickListener
            runOnUiThread {
                btnFetch.isEnabled = false
                btnFetch.alpha = 0.5f
                tvStatus.text = "ログブック取得中..."
            }
            // Trigger auth token capture by navigating to a POTA API page
            webView.evaluateJavascript("fetch('https://api.pota.app/profile/JA0').then(()=>{})", null)
        }

        webView.loadUrl("https://pota.app")
    }

    private fun handleRequest(request: WebResourceRequest): WebResourceResponse? {
        if (!navigated || fetchDone) return null
        val url = request.url.toString()
        if (!url.contains("api.pota.app")) return null
        val auth = request.requestHeaders["Authorization"]
            ?: request.requestHeaders["authorization"] ?: return null
        if (huntCaptured) return null

        huntCaptured = true
        runOnUiThread {
            tvStatus.text = "ログブック取得中..."
            btnFetch.isEnabled = false
            btnFetch.alpha = 0.5f
        }
        Thread {
            val result = fetchLogbook(auth)
            fetchDone = true
            val intent = Intent().putExtra(INTENT_RESULT_JSON, result)
            setResult(Activity.RESULT_OK, intent)
            runOnUiThread {
                tvStatus.text = "取得完了。閉じます..."
                finish()
            }
        }.start()
        return null
    }

    private fun fetchLogbook(authToken: String): String {
        return try {
            val parks = mutableMapOf<String, JSONObject>()
            var page = 1
            val size = 100
            var totalCount = Int.MAX_VALUE
            while ((page - 1) * size < totalCount) {
                val totalPages = if (totalCount == Int.MAX_VALUE) "?" else "${(totalCount + size - 1) / size}"
                runOnUiThread { tvStatus.text = "ページ $page / $totalPages 取得中..." }
                val req = okhttp3.Request.Builder()
                    .url("https://api.pota.app/user/logbook?hunterOnly=1&page=$page&size=$size")
                    .addHeader("Authorization", authToken)
                    .build()
                val resp = okHttp.newCall(req).execute()
                if (!resp.isSuccessful) { resp.body?.close(); break }
                val body = resp.body?.string() ?: break
                val json = JSONObject(body)
                val entries = json.optJSONArray("entries") ?: break
                totalCount = json.optInt("count", 0)
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val ref = e.optString("reference").ifEmpty { continue }
                    val locDesc = e.optString("locationDesc").ifEmpty { null }
                    val parkName = e.optString("name").ifEmpty { null }
                    val mode = e.optString("mode").uppercase().ifEmpty { null }
                    val band = e.optString("band").ifEmpty { null }
                    val key = if (locDesc != null) "$ref|$locDesc" else ref
                    val existing = parks[key]
                    if (existing != null) {
                        existing.put("qsos", existing.optInt("qsos") + 1)
                    } else {
                        val obj = JSONObject().apply {
                            put("reference", ref)
                            if (locDesc != null) put("short", locDesc)
                            if (parkName != null) put("park", parkName)
                            put("qsos", 1)
                            if (mode != null) put("modes", JSONArray().put(mode))
                            if (band != null) put("bands", JSONArray().put(band))
                        }
                        parks[key] = obj
                    }
                }
                if (entries.length() == 0) break
                page++
            }
            JSONArray(parks.values).toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
        okHttp.dispatcher.executorService.shutdown()
    }
}
