package com.ji1ore.wifi_rig_ctrl

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.ji1ore.wifi_rig_ctrl.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHelpBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.help_title)
        binding.webView.apply {
            settings.javaScriptEnabled = false
            webViewClient = WebViewClient()
            val lang = java.util.Locale.getDefault().language
            val file = if (lang == "ja") "help_ja.html" else "help.html"
            loadUrl("file:///android_asset/$file")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
