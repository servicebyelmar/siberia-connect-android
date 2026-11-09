package com.v2ray.ang.ui.sc

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Siberia Connect: главный экран приложения.
 * Интерфейс — наш кабинет (sibirapp.duckdns.org/app/), VPN — встроенный движок Xray.
 * Страница управляет VPN через мост window.SCNative.
 */
class ScActivity : ComponentActivity() {

    companion object {
        const val HOST = "sibirapp.duckdns.org"
        const val HOME = "https://$HOST/app/?native=1"
        const val SUB_NAME = "Siberia Connect"
    }

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("sc_app", MODE_PRIVATE) }

    @Volatile
    private var running = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startCore()
            else emit(JSONObject().put("type", "state").put("state", "off").put("error", "Нужно разрешить VPN-подключение"))
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            when (i.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    running = true
                    if (prefs.getLong("since", 0L) == 0L) prefs.edit().putLong("since", System.currentTimeMillis()).apply()
                    emit(JSONObject().put("type", "state").put("state", "on"))
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    running = false
                    prefs.edit().remove("since").apply()
                    emit(JSONObject().put("type", "state").put("state", "off"))
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    running = false
                    emit(JSONObject().put("type", "state").put("state", "error")
                        .put("error", i.getStringExtra("content").orEmpty()))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = 0xFF081A3D.toInt()
            window.navigationBarColor = 0xFF060F2A.toInt()
        }
        web = WebView(this)
        web.setBackgroundColor(0xFF060F2A.toInt())
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString SiberiaConnectApp/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(Bridge(), "SCNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if ((u.scheme == "https" || u.scheme == "http") && u.host == HOST) return false
                openExternal(u.toString())
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.scBack && window.scBack()) ? '1' : '0'") { r ->
                    if (r?.contains("1") != true) moveTaskToBack(true)
                }
            }
        })
        if (savedInstanceState == null) web.loadUrl(HOME) else web.restoreState(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { ensureGeoFiles() }
        // apk_v4: скорость в уведомлении включена по умолчанию (один раз)
        if (!prefs.getBoolean("defaults_v4", false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
            prefs.edit().putBoolean("defaults_v4", true).apply()
        }

        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        super.onDestroy()
    }

    private fun emit(ev: JSONObject) {
        runOnUiThread {
            if (::web.isInitialized) {
                web.evaluateJavascript("window.scNative && window.scNative($ev)", null)
            }
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            emit(JSONObject().put("type", "toast").put("text", "Не удалось открыть ссылку"))
        }
    }

    private fun devId(): String {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "none"
        val md = MessageDigest.getInstance("SHA-256").digest("sc:$raw:${BuildConfig.APPLICATION_ID}".toByteArray())
        return "and-" + md.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun ourSub(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == SUB_NAME }

    private fun applySub(url: String): JSONObject {
        val out = JSONObject().put("type", "sub")
        if (!url.startsWith("https://")) return out.put("ok", false).put("error", "Неверная ссылка подписки")
        val existing = ourSub()
        val item = existing?.subscription ?: SubscriptionItem()
        item.remarks = SUB_NAME
        item.url = url
        item.enabled = true
        item.autoUpdate = true
        item.updateInterval = 60
        item.requestHeaders = JSONObject()
            .put("x-hwid", devId())
            .put("x-device-os", "Android")
            .put("x-ver-os", Build.VERSION.RELEASE ?: "")
            .put("x-device-model", Build.MODEL ?: "")
            .toString()
        MmkvManager.encodeSubscription(existing?.guid ?: "", item)
        val sub = ourSub() ?: return out.put("ok", false).put("error", "Не удалось сохранить подписку")
        val res = AngConfigManager.updateConfigViaSub(sub)
        runCatching { SubscriptionUpdater.syncOne(applicationContext, sub.guid) }
        val list = MmkvManager.decodeServerList(sub.guid)
        val sel = MmkvManager.getSelectServer()
        if (list.isNotEmpty() && (sel == null || !list.contains(sel))) MmkvManager.setSelectServer(list.first())
        return out.put("ok", list.isNotEmpty()).put("count", list.size).put("updated", res.successCount)
            .put("error", if (list.isEmpty()) "Сервер не выдал список. Проверь интернет или лимит устройств" else "")
    }

    private fun requestStart() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            emit(JSONObject().put("type", "state").put("state", "error").put("error", "Сначала обнови ключ"))
            return
        }
        if (!SettingsManager.isVpnMode()) {
            startCore()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startCore() else vpnPermission.launch(intent)
    }

    private fun startCore() {
        emit(JSONObject().put("type", "state").put("state", "connecting"))
        prefs.edit().remove("since").apply()
        lifecycleScope.launch(Dispatchers.IO) {
            ensureGeoFiles()
            runOnUiThread { LauncherManager.startService(this@ScActivity) }
        }
    }

    /** apk_v3: раскладываем geosite.dat / geoip.dat (раньше это делал только экран v2rayNG). */
    private fun ensureGeoFiles() {
        runCatching {
            val dir = java.io.File(Utils.userAssetPath(this))
            dir.mkdirs()
            assets.list("")?.filter { it.startsWith("geo") && it.endsWith(".dat") }?.forEach { n ->
                val f = java.io.File(dir, n)
                if (!f.exists() || f.length() == 0L) {
                    assets.open(n).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun deviceId(): String = devId()

        @JavascriptInterface
        fun isRunning(): Boolean = running

        @JavascriptInterface
        fun hasSub(): Boolean = ourSub()?.let { MmkvManager.decodeServerList(it.guid).isNotEmpty() } ?: false

        @JavascriptInterface
        fun subUrl(): String = ourSub()?.subscription?.url.orEmpty()

        @JavascriptInterface
        fun setSub(url: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val r = try {
                    applySub(url)
                } catch (e: Exception) {
                    JSONObject().put("type", "sub").put("ok", false).put("error", e.message ?: "Ошибка")
                }
                emit(r)
            }
        }

        @JavascriptInterface
        fun connect() {
            runOnUiThread { requestStart() }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { LauncherManager.stopService(this@ScActivity) }
        }

        @JavascriptInterface
        fun servers(): String {
            val sub = ourSub() ?: return "[]"
            val sel = MmkvManager.getSelectServer()
            val arr = JSONArray()
            MmkvManager.decodeServerList(sub.guid).forEach { g ->
                val p = MmkvManager.decodeServerConfig(g) ?: return@forEach
                arr.put(JSONObject().put("guid", g).put("name", p.remarks).put("selected", g == sel))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun select(guid: String) {
            runOnUiThread {
                MmkvManager.setSelectServer(guid)
                if (running) LauncherManager.restartService(this@ScActivity)
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { this@ScActivity.openExternal(url) }
        }

        /** apk_v4: трафик приложения (весь VPN идёт через него) и время подключения */
        @JavascriptInterface
        fun stats(): String {
            val uid = android.os.Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            return JSONObject().put("rx", rx).put("tx", tx).put("since", prefs.getLong("since", 0L))
                .put("now", System.currentTimeMillis()).toString()
        }

        @JavascriptInterface
        fun autoStart(): Boolean = MmkvManager.decodeStartOnBoot()

        @JavascriptInterface
        fun setAutoStart(on: Boolean) {
            MmkvManager.encodeStartOnBoot(on)
        }

        @JavascriptInterface
        fun vpnSettings() {
            runOnUiThread {
                runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    .onFailure { emit(JSONObject().put("type", "toast").put("text", "Открой Настройки → VPN вручную")) }
            }
        }

        @JavascriptInterface
        fun advanced() {
            runOnUiThread { startActivity(Intent(this@ScActivity, MainActivity::class.java)) }
        }
    }
}
