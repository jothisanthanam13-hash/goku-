package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = false) {
        FiddyScreen()
      }
    }
  }
}

class WebAppInterface(private val context: Context) {
  @JavascriptInterface
  fun vibrate(milliseconds: Long) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator?.vibrate(
            VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
        )
      } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vibrator?.vibrate(
              VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
          )
        } else {
          @Suppress("DEPRECATION")
          vibrator?.vibrate(milliseconds)
        }
      }
    } catch (_: Exception) {
      // Graceful fallback
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FiddyScreen() {
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }

  BackHandler(enabled = true) {
    if (webViewInstance?.canGoBack() == true) {
      webViewInstance?.goBack()
    } else {
      webViewInstance?.evaluateJavascript(
          "if (document.querySelector('.modal-backdrop.open')) { " +
              "  document.querySelectorAll('.modal-backdrop.open').forEach(m => m.classList.remove('open')); " +
              "}",
          null
      )
    }
  }

  Box(
      modifier = Modifier
          .fillMaxSize()
          .background(androidx.compose.ui.graphics.Color(0xFFFFFFFF))
          .statusBarsPadding()
  ) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            webViewInstance = this
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              loadWithOverviewMode = true
              useWideViewPort = true
              builtInZoomControls = false
              displayZoomControls = false
              cacheMode = WebSettings.LOAD_DEFAULT
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                  view: WebView?,
                  request: WebResourceRequest?
              ): Boolean {
                return false
              }
            }
            addJavascriptInterface(WebAppInterface(ctx), "Android")
            loadUrl("file:///android_asset/index.html")
          }
        }
    )
  }
}
