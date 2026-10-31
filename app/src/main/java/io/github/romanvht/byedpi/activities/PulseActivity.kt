package io.github.romanvht.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.utility.getPreferences
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Pulse для Android: веб-версия Pulse во встроенном браузере, а весь её трафик идёт через byedpi
 * в режиме прокси (127.0.0.1:1080). Так YouTube работает без VPN, а остальные приложения телефона не затронуты.
 * Без вопроса «Разрешить VPN?» — прокси работает только внутри Pulse.
 */
class PulseActivity : Activity() {
    companion object {
        const val HOME = "https://interium-rus.github.io/pulse/app/"
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 1080
        private const val REQ_FILE = 41
        private const val REQ_MIC = 42
        private const val REQ_VOICE = 43
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMic: PermissionRequest? = null
    private var fullView: View? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val bypassOn get() = getPreferences().getBoolean("pulse_bypass", true)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 5, 13)
        window.navigationBarColor = Color.rgb(6, 5, 13)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 5, 13)) }
        web = WebView(this).apply { setBackgroundColor(Color.rgb(6, 5, 13)) }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString PulseAndroid/2"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "PulseAndroid")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        prepareBypassPrefs()
        val start = intent?.data?.toString()?.takeIf { it.startsWith(HOME) } ?: HOME
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else startWithBypass(start)
    }

    // настройки byedpi под Pulse: режим прокси, при первом запуске — способ по умолчанию
    private fun prepareBypassPrefs() {
        val prefs = getPreferences()
        prefs.edit(commit = true) {
            putString("byedpi_mode", "proxy")
            putString("byedpi_proxy_ip", PROXY_HOST)
            putString("byedpi_proxy_port", PROXY_PORT.toString())
        }
    }

    private fun startWithBypass(url: String) {
        if (!bypassOn || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            clearProxy { web.loadUrl(url) }
            return
        }
        ServiceManager.start(this, Mode.Proxy)
        // ждём, пока byedpi откроет порт (до 4 секунд), потом направляем браузер через него
        io.execute {
            val ready = (0 until 40).any { waitPort() }
            main.post {
                if (!ready) { clearProxy { web.loadUrl(url) }; return@post }
                val cfg = ProxyConfig.Builder()
                    .addProxyRule("socks5://$PROXY_HOST:$PROXY_PORT")
                    .addBypassRule("<local>")
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, { it.run() }) { web.loadUrl(url) }
            }
        }
    }

    private fun waitPort(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), 100) }; true
    } catch (_: Exception) { Thread.sleep(100); false }

    private fun clearProxy(then: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            ProxyController.getInstance().clearProxyOverride({ it.run() }) { then() }
        else then()
    }

    // ссылки на сайт Pulse (возврат после входа через Google) открываются здесь же
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.data?.toString() ?: return
        if (url.startsWith(HOME)) web.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullView != null) { (web.webChromeClient as Chrome).onHideCustomView(); return }
        // «Назад» на телефоне: сначала закрывает окна и медиатеку внутри Pulse
        web.evaluateJavascript("window.pulseBack ? window.pulseBack() : false") { handled ->
            if (handled != "true") { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
        }
    }

    override fun onDestroy() {
        if (isFinishing) ServiceManager.stop()
        web.destroy()
        super.onDestroy()
    }

    private fun openOutside(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false // плеер YouTube и прочие рамки живут внутри
            val u = request.url
            val s = u.toString()
            // своя страница и вход Supabase — внутри; Google-вход, Telegram и прочие сайты — снаружи
            if (s.startsWith(HOME)) return false
            if (u.host == "accounts.google.com" || u.scheme != "https" || u.host?.endsWith("supabase.co") != true) {
                openOutside(u); return true
            }
            return false
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) { request.deny(); return }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingMic = request
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try { startActivityForResult(params.createIntent(), REQ_FILE); true }
            catch (_: Exception) { fileCallback = null; false }
        }

        // видео YouTube на весь экран
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullView = view
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            web.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        override fun onHideCustomView() {
            fullView?.let { root.removeView(it) }
            fullView = null
            web.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
        if (requestCode == REQ_VOICE) {
            voiceResult(if (resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null)
        }
    }

    private fun voiceResult(text: String?) {
        val arg = if (text == null) "null" else JSONObject.quote(text)
        web.evaluateJavascript("window.pulseVoice && window.pulseVoice($arg)", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val req = pendingMic ?: return
            pendingMic = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }
    }

    // мост для страницы: включить/выключить ускорение, подобрать способ под оператора
    private inner class Bridge {
        @JavascriptInterface fun isBypass(): Boolean = bypassOn

        @JavascriptInterface fun setBypass(on: Boolean) {
            getPreferences().edit(commit = true) { putBoolean("pulse_bypass", on) }
            main.post {
                if (!on) ServiceManager.stop()
                startWithBypass(web.url ?: HOME)
            }
        }

        @JavascriptInterface fun openBypassTest() {
            main.post { startActivity(Intent(this@PulseActivity, TestActivity::class.java)) }
        }

        @JavascriptInterface fun openBypassSettings() {
            main.post { startActivity(Intent(this@PulseActivity, SettingsActivity::class.java)) }
        }

        // скачивание треков со свободной лицензией в «Музыка/Pulse» (только Архив интернета, ccMixter и Jamendo)
        @JavascriptInterface fun download(url: String, name: String) {
            val u = Uri.parse(url)
            val host = u.host ?: return
            if (u.scheme != "https" || listOf("archive.org", "ccmixter.org", "jamendo.com").none { host == it || host.endsWith(".$it") }) return
            val file = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(120).ifBlank { "track.mp3" }
            main.post {
                try {
                    val req = DownloadManager.Request(u)
                        .setTitle(file)
                        .setDescription("Pulse")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    try { req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Pulse/$file") }
                    catch (_: Exception) { req.setDestinationInExternalFilesDir(this@PulseActivity, Environment.DIRECTORY_MUSIC, file) }
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                } catch (_: Exception) { openOutside(u) }
            }
        }

        // голосовая команда: системный голосовой ввод, текст возвращается в window.pulseVoice
        @JavascriptInterface fun listen() {
            main.post {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи команду для Pulse")
                try { startActivityForResult(i, REQ_VOICE) }
                catch (_: Exception) { web.evaluateJavascript("window.pulseVoiceFail && window.pulseVoiceFail()", null) }
            }
        }

        @JavascriptInterface fun version(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
}
