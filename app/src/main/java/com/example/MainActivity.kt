package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
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
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun configureAppCheck(context: Context, intent: Intent?) {
  try {
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(context)
    }

    if (BuildConfig.DEBUG) {
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
      if (!debugToken.isNullOrEmpty()) {
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs =
            context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
        prefs.edit().putString(DEBUG_SECRET_KEY, debugToken).commit()
        Log.d(TAG, "App Check debug secret configured successfully.")
      } else {
        Log.w(TAG, "No debug token found in Intent extra.")
      }

      FirebaseAppCheck.getInstance()
          .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
      Log.d(TAG, "DebugAppCheckProviderFactory installed.")
    } else {
      val resId =
          context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
      if (resId != 0) {
        val siteKey = context.getString(resId)
        if (siteKey.isNotBlank()) {
          FirebaseAppCheck.getInstance()
              .installAppCheckProviderFactory(
                  RecaptchaAppCheckProviderFactory.getInstance(siteKey)
              )
          Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
        }
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to initialize Firebase App Check", e)
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = false) {
        FiddyScreen()
      }
    }
  }
}

class WebAppInterface(private val context: Context, private val webViewProvider: () -> WebView?) {
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

  @JavascriptInterface
  fun isFirebaseAiAvailable(): Boolean {
    return true
  }

  @JavascriptInterface
  fun analyzeMealWithGemini(mealDesc: String, base64Data: String?, callbackName: String) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val model = Firebase.ai.generativeModel("gemini-3.8-flash")
        val promptText =
            "Analyze this Indian meal. Return strictly valid JSON containing: {\"dishName\": string, \"calories\": number, \"protein_g\": number, \"carbs_g\": number, \"fat_g\": number, \"fiber_g\": number, \"servingSize\": string} with no markdown formatting and no codeblocks."

        val response = withTimeout(60_000L) {
          if (!base64Data.isNullOrBlank()) {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            model.generateContent(
                content {
                  if (bitmap != null) image(bitmap)
                  text("$promptText\nMeal Description: $mealDesc")
                }
            )
          } else {
            model.generateContent("$promptText\nMeal Description: $mealDesc")
          }
        }

        val raw = response.text ?: "{}"
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val escaped = JSONObject.quote(cleaned)

        withContext(Dispatchers.Main) {
          webViewProvider()?.evaluateJavascript("$callbackName($escaped, null)", null)
        }
      } catch (e: Exception) {
        Log.e("FirebaseAi", "Gemini analysis failed", e)
        val errMsg = JSONObject.quote(e.localizedMessage ?: "AI analysis failed")
        withContext(Dispatchers.Main) {
          webViewProvider()?.evaluateJavascript("$callbackName(null, $errMsg)", null)
        }
      }
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
            addJavascriptInterface(WebAppInterface(ctx) { webViewInstance }, "Android")
            loadUrl("file:///android_asset/index.html")
          }
        }
    )
  }
}
