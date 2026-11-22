package com.v2ray.ang.ui.sc

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ScLock
import com.v2ray.ang.handler.ScTelemetry
import com.v2ray.ang.handler.ScTelemetryGate
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Siberia Connect: главный экран приложения.
 * Интерфейс — наш кабинет (sibirapp.duckdns.org/app/), VPN — встроенный движок Xray.
 * Страница управляет VPN через мост window.SCNative.
 */
class ScActivity : ComponentActivity() {

    companion object {
        const val BRAND_BG = 0xFF0B0D10.toInt()
        const val PRIMARY_HOST = "sibirapp.duckdns.org"
        const val FALLBACK_HOST = "sibir-cloud-api.ru:9443"
        const val PRIMARY_HOME = "https://$PRIMARY_HOST/app/?native=1"
        const val FALLBACK_HOME = "https://$FALLBACK_HOST/app/?native=1"
        const val SUB_NAME = "Siberia Connect"

        /** apk_v5: российские приложения, которые по умолчанию идут мимо VPN */
        val RU_DIRECT = setOf(
            // банки и платежи
            "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android",
            "ru.raiffeisen.rmobile", "ru.gazprombank.android.mobilebank.app", "com.openbank", "ru.rosbank.android",
            "ru.psbank.online", "ru.mts.money", "ru.pochtabank.pochtaapp", "ru.sovcombank.halvacard", "com.yandex.bank",
            "ru.ozon.fintech.finance", "ru.nspk.mirpay", "ru.nspk.sbpay", "ru.sberbank.sberbankid", "ru.uralsib.mb",
            "ru.akbars.mobile", "ru.bspb", "ru.mkb.mobile", "ru.rshb.dbo", "ru.unicredit.android", "ru.homecredit.mycredit",
            "ru.otpbank.mobile", "ru.rencredit.android", "ru.letobank.Prometheus", "com.qiwi.wallet", "ru.yoo.money",
            // госсервисы
            "ru.rostel", "ru.gosuslugi.goskey", "ru.fns.lkfl", "com.gnivts.selfemployed", "ru.mos.app", "ru.pfr.mobile",
            // маркетплейсы и магазины
            "com.wildberries.ru", "ru.ozon.app.android", "ru.beru.android", "ru.sbermegamarket.app", "com.avito.android",
            "ru.vkusvill", "ru.perekrestok.app", "ru.myspar", "ru.magnit.express.android", "ru.lenta.lentochka", "ru.dns.shop.android",
            "ru.mvideo.mobile", "ru.eldorado.app", "ru.lamoda.android", "ru.goldapple", "ru.detmir.dmbonus", "ru.samokat.mobile",
            // такси, карты, доставка, транспорт
            "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.foodfox.client", "ru.yandex.lavka",
            "com.deliveryclub", "ru.dodopizza.app", "ru.dublgis.dgismobile", "ru.rzd.pass", "ru.tutu.tutu_emp", "com.citymobil",
            "ru.yandex.metro", "ru.yandex.yandexbus", "ru.aviasales",
            // Яндекс, VK, связь, остальное
            "ru.yandex.searchplugin", "com.yandex.browser", "ru.yandex.mail", "ru.yandex.disk", "ru.yandex.music",
            "ru.kinopoisk", "com.vkontakte.android", "ru.ok.android", "ru.mail.mailapp", "ru.rutube.app", "ru.vk.store",
            "com.vk.vkvideo", "ru.oneme.app", "ru.mts.mymts", "ru.beeline.services", "ru.megafon.mlk", "ru.tele2.mytele2",
            "ru.hh.android", "ru.yota.android", "ru.gismeteo.gismeteo"
        )

        /** apk_v6: приложения, которым нужен VPN (заблокированы или тормозят в РФ). Всё остальное — напрямую. */
        val NEED_VPN = setOf(
            "com.google.android.youtube", "com.google.android.apps.youtube.music", "com.google.android.youtube.tv",
            "app.revanced.android.youtube", "app.rvx.android.youtube", "com.vanced.android.youtube",
            "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "org.telegram.plus", "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.discord", "com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard", "ai.x.grok",
            "ai.perplexity.app.android", "com.microsoft.copilot", "com.netflix.mediaclient", "com.spotify.music",
            "com.linkedin.android", "com.reddit.frontpage", "com.pinterest", "tv.twitch.android.app", "com.snapchat.android",
            "org.thoughtcrime.securesms", "com.viber.voip", "com.skype.raider", "com.patreon.android", "com.medium.reader",
            "com.soundcloud.android", "com.deezer.android.app", "com.tumblr", "com.quora.android", "com.duolingo",
            "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "org.mozilla.firefox_beta", "com.opera.browser",
            "com.opera.mini.native", "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
            "com.google.android.apps.photos", "com.google.android.gm", "com.google.android.apps.docs", "com.google.android.apps.translate"
        )

        /** apk_v6: маршрутизация — российские сайты и адреса напрямую (в браузере и везде). */
        /** smart_v1: без «российские сайты напрямую» — только служебные правила (локальная сеть, торренты, QUIC) */
        const val BASE_ROUTING = """[
 {"remarks":"Bypass bittorrent","outboundTag":"direct","protocol":["bittorrent"]},
 {"remarks":"Block udp443","outboundTag":"block","port":"443","network":"udp"},
 {"remarks":"Direct LAN IP","outboundTag":"direct","ip":["geoip:private"]},
 {"remarks":"Direct LAN domains","outboundTag":"direct","domain":["geosite:private"]}
]"""

        const val RU_ROUTING = """[
 {"remarks":"Bypass bittorrent","outboundTag":"direct","protocol":["bittorrent"]},
 {"remarks":"Block udp443","outboundTag":"block","port":"443","network":"udp"},
 {"remarks":"Direct LAN IP","outboundTag":"direct","ip":["geoip:private"]},
 {"remarks":"Direct LAN domains","outboundTag":"direct","domain":["geosite:private"]},
 {"remarks":"Siberia Connect: российские сайты напрямую","outboundTag":"direct","domain":[
  "domain:ru","domain:su","domain:xn--p1ai","domain:xn--80adxhks","domain:xn--d1acj3b",
  "domain:yandex.net","domain:yandex.com","domain:yastatic.net","domain:yandexcloud.net","domain:yandex.cloud",
  "domain:vk.com","domain:vk.me","domain:vkuser.net","domain:userapi.com","domain:vk-cdn.net","domain:vk-portal.net","domain:vkvideo.ru",
  "domain:mycdn.me","domain:okcdn.ru","domain:mail.ru","domain:imgsmail.ru","domain:mradx.net",
  "domain:2gis.com","domain:avito.st","domain:wbstatic.net","domain:wbbasket.ru","domain:ozone.ru","domain:ozonusercontent.com",
  "domain:sberbank.com","domain:sber.ru","domain:sbrf.ru","domain:tbank-online.com","domain:tinkoffjournal.ru",
  "domain:kaspersky.com","domain:drweb.com","domain:1c.ru","domain:mos.ru","domain:nalog.gov.ru","domain:gosuslugi.ru",
  "domain:rutube.ru","domain:dzen.ru","domain:kinopoisk.ru","domain:ivi.ru","domain:okko.tv","domain:start.ru","domain:wink.ru",
  "domain:max.ru","domain:oneme.ru","domain:rustore.ru","domain:hh.ru","domain:rzd.ru","domain:tutu.ru",
  "geosite:category-ru"]},
 {"remarks":"Siberia Connect: российские адреса напрямую","outboundTag":"direct","ip":["geoip:ru"]}
]"""
    }

    private lateinit var web: WebView
    private val wolfAssets by lazy { ScWolfAssets(this) }
    private val prefs by lazy { getSharedPreferences("sc_app", MODE_PRIVATE) }
    private val homeHandler = Handler(Looper.getMainLooper())
    private var homeLoaded = false
    private var fallbackTried = false
    private var currentHome = PRIMARY_HOME
    private val allowedAuthorities get() = setOf(PRIMARY_HOST, FALLBACK_HOST).filter { it.isNotBlank() }

    /** unified_v1: резервный адрес кабинета с теми же параметрами (вход по ссылке не теряется). */
    private fun fallbackOf(url: String): String =
        if (url.startsWith("https://$PRIMARY_HOST/")) url.replaceFirst("https://$PRIMARY_HOST/", "https://$FALLBACK_HOST/") else FALLBACK_HOME

    private fun loadHome(url: String, fallback: Boolean = false) {
        currentHome = url
        homeLoaded = false
        fallbackTried = fallback
        web.loadUrl(url)
        homeHandler.removeCallbacksAndMessages(null)
        homeHandler.postDelayed({
            if (!homeLoaded && !fallbackTried && FALLBACK_HOST.isNotBlank() && FALLBACK_HOST != PRIMARY_HOST) {
                loadHome(fallbackOf(url), fallback = true)
            }
        }, 3_000)
    }

    /** 1.1.0: вход без Telegram, единый статус, связка с Telegram (сервер: id_v1) */
    /** Токен, секрет установки, VPN-ключ и блокировка — только в зашифрованном хранилище. */
    private val secure by lazy { ScSecureStore.prefs(this) }
    private val account by lazy { ScAccount(secure) { devId() } }

    private val activation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val link = r.data?.getStringExtra(ScActivationActivity.EXTRA_LINK)
            // 1.2.6: вход выполнен и без ссылки (подписка не активна) — статус покажет причину
            if (r.resultCode == RESULT_OK) {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (!link.isNullOrEmpty()) runCatching { applySubAny(link) }
                    runOnUiThread { if (::web.isInitialized) web.reload() }   // onPageFinished → syncToken отдаст странице НОВЫЙ вход
                    connectFlow()
                }
            }
        }

    private fun openActivation() = runOnUiThread { activation.launch(ScActivationActivity.intent(this)) }

    /** Явный отказ сервера (401/403/DEVICE_LOCKED/Key Expired): убираем локальный ключ и серверы. */
    private fun dropLocalKey() {
        ourSub()?.let { runCatching { MmkvManager.removeSubscription(it.guid) } }
        ScSecureStore.clearKey(this)
    }

    /** Ключ пропал из списка (сбой хранилища), но сервер его не отзывал — ставим сохранённый. */
    private fun restoreKeyIfMissing() {
        if (hasSubNow()) return
        val k = ScSecureStore.key(this)
        if (k.isNotEmpty() && ScLock.isAllowedSubUrl(k)) applySubAny(k)
    }

    /** 1.1.0: «Северное Сияние (Авто)» — задержка последней проверки по каждому серверу, мс (-1 — не ответил) */
    private val pings = ConcurrentHashMap<String, Long>()

    @Volatile
    private var preparing = false

    @Volatile
    private var pinging = false
    private var lastFailover = 0L

    @Volatile
    private var running = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startCore()
            else emit(JSONObject().put("type", "state").put("state", "off").put("error", "Нужно разрешить VPN-подключение"))
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ScTelemetry.noteWake()
        }
    }

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
                    // 1.2.7: был включён и остановился сам (не пользователь и не приложение) — «обрыв сессии»; фильтры — в ScTelemetry
                    if (running) {
                        ScTelemetry.log("vpn stopped")
                        ScTelemetry.report(this@ScActivity, ScTelemetryGate.SESSION_DROPPED, ScTelemetry.locationOf(MmkvManager.getSelectServer()), 6_000L)
                    }
                    running = false
                    prefs.edit().remove("since").apply()
                    emit(JSONObject().put("type", "state").put("state", "off"))
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    running = false
                    // 1.1.0: в «Авто» один раз за 3 минуты сами пробуем другой сервер
                    if (autoMode() && System.currentTimeMillis() - lastFailover > 180_000L && serverCount() > 1) {
                        lastFailover = System.currentTimeMillis()
                        emit(JSONObject().put("type", "toast").put("text", "Сервер не ответил — выбираю другой"))
                        connectFlow()
                        return
                    }
                    // 1.2.7: спокойный короткий статус; технический текст ядра уходит только в журнал отчёта (очищенный)
                    ScTelemetry.log("core start failure: " + i.getStringExtra("content").orEmpty().take(200))
                    ScTelemetry.report(this@ScActivity, ScTelemetryGate.TUNNEL_TIMEOUT, ScTelemetry.locationOf(MmkvManager.getSelectServer()))
                    emit(JSONObject().put("type", "state").put("state", "error")
                        .put("error", "Не удалось подключиться. Попробуйте сменить локацию"))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            // единый графитовый фон Wolf Standard (Windows / Mini App): бары сливаются с интерфейсом
            window.statusBarColor = BRAND_BG
            window.navigationBarColor = BRAND_BG
        }
        // тема приложения светлая (windowLightStatusBar=true) — на тёмном фоне нужны светлые значки
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        web = WebView(this)
        web.setBackgroundColor(BRAND_BG)
        // Android 15+ (targetSdk 35+) принудительно рисует окно под системными барами, а цвета баров игнорирует.
        // Единое поведение на всех версиях: edge-to-edge + отступы под статус-бар, вырез, панель навигации и клавиатуру.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(BRAND_BG) }
        frame.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val i = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)
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
                val uri = request.url
                if ((uri.scheme == "https" || uri.scheme == "http") && uri.authority in allowedAuthorities) return false
                openExternal(uri.toString())
                return true
            }

             override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                 wolfAssets.intercept(request) ?: super.shouldInterceptRequest(view, request)

             override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (uri?.authority in allowedAuthorities && uri?.path?.startsWith("/app/") == true) {
                    homeLoaded = true
                    homeHandler.removeCallbacksAndMessages(null)
                }
                if (uri?.authority in allowedAuthorities) {
                    val did = JSONObject.quote(devId())
                    val hw = JSONObject.quote(ScSecureStore.hw)
                    view.evaluateJavascript("try{localStorage.setItem('sc_gdid',$did);if($hw)localStorage.setItem('sc_hw',$hw)}catch(e){}", null)
                }
                syncToken()   // 1.1.0: общий вход приложения и кабинета
            }


            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                val authority = runCatching { Uri.parse(failingUrl).authority }.getOrNull()
                if (!fallbackTried && authority in allowedAuthorities && authority == PRIMARY_HOST && FALLBACK_HOST.isNotBlank() && FALLBACK_HOST != PRIMARY_HOST) {
                    loadHome(fallbackOf(currentHome), fallback = true)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.scBack && window.scBack()) ? '1' : '0'") { r ->
                    if (r?.contains("1") != true) moveTaskToBack(true)
                }
            }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (account.needsAutoRegister(hasSubNow()) && dlCode(intent) == null) {
            // 1.1.0: первый запуск без ключа — аккаунт и пробный день создаются сами, кабинет откроется уже с входом
            val start = dlUrl(intent) ?: PRIMARY_HOME
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    if (account.autoRegister()) applyActiveLink(account.last)
                }
                runOnUiThread { loadHome(start) }
            }
        } else loadHome(dlUrl(intent) ?: PRIMARY_HOME)
        // ключ мог пропасть из списка при сбое хранилища — возвращаем сохранённый (сеть тут ни при чём)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { restoreKeyIfMissing() } }
        // идёт 72-часовая блокировка — сразу показываем таймер
        if (ScSecureStore.lockUntil(this) > System.currentTimeMillis() && !hasSubNow()) openActivation()
        lifecycleScope.launch(Dispatchers.IO) { ensureGeoFiles() }
        // apk_v4: скорость в уведомлении включена по умолчанию (один раз)
        lifecycleScope.launch(Dispatchers.IO) { syncDirectApps(firstRun = !prefs.getBoolean("defaults_v6", false)) }
        if (!prefs.getBoolean("defaults_v4", false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
            prefs.edit().putBoolean("defaults_v4", true).apply()
        }

        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags()
        )
        // 1.2.7: фоновая телеметрия — устройство проснулось (первые 10 с после этого сбоем не считаются) и код обращения для экрана
        ContextCompat.registerReceiver(this, wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        ScTelemetry.onIncident = { code -> emit(JSONObject().put("type", "incident").put("code", code)) }
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
        // upd_v1: вернулись из настроек с разрешением — сразу открываем установщик
        if (updPending && updCanInstall()) { updPending = false; updFile?.let { updInstall(it) } }
        updCheckNative()
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        syncToken()
        // 1.2.7: сбой случился, пока приложение было в фоне, и VPN сейчас не включён — показываем код обращения (10 минут)
        if (!running) ScTelemetry.recentIncident(10 * 60_000L)?.let { emit(JSONObject().put("type", "incident").put("code", it)) }
    }

    override fun onDestroy() {
        homeHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(wakeReceiver) }
        ScTelemetry.onIncident = null
        runCatching { MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()   // освобождает и видеоплеер страницы
        }
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
        return ScSecureStore.deviceUuid(this)
    }

    private fun ourSub(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == SUB_NAME && ScLock.isOurSub(it) }

    private fun applySub(url: String): JSONObject {
        val out = JSONObject().put("type", "sub")
        if (!ScLock.isAllowedSubUrl(url)) return out.put("ok", false).put("error", ScLock.DENIED_KEY)
        val existing = ourSub()
        val item = existing?.subscription?.copy() ?: SubscriptionItem()
        item.remarks = SUB_NAME
        item.url = url
        item.enabled = true
        item.autoUpdate = true
        item.updateInterval = 60
        item.filter = null
        item.requestHeaders = JSONObject()
            .put("x-hwid", devId())
            .put("x-device-os", "Android")
            .put("x-ver-os", Build.VERSION.RELEASE ?: "")
            .put("x-device-model", Build.MODEL ?: "")
            .toString()
        MmkvManager.encodeSubscription(existing?.guid ?: "", item)
        val sub = ourSub() ?: return out.put("ok", false).put("error", "Не удалось сохранить подписку")
        val res = AngConfigManager.updateConfigViaSub(sub)
        if (res.successCount == 0) {
            existing?.let { MmkvManager.encodeSubscription(it.guid, it.subscription) }
            return out.put("ok", false).put("error", "Не удалось обновить список серверов")
        }
        runCatching { SubscriptionUpdater.syncOne(applicationContext, sub.guid) }
        val list = MmkvManager.decodeServerList(sub.guid)
        val sel = MmkvManager.getSelectServer()
        if (list.isNotEmpty() && (sel == null || !list.contains(sel))) MmkvManager.setSelectServer(list.first())
        return out.put("ok", list.isNotEmpty()).put("count", list.size).put("updated", res.successCount)
            .put("error", if (list.isEmpty()) "Сервер не выдал список. Проверь интернет или лимит устройств" else "")
    }

    /**
     * unified_v1: та же подписка с резервом — основной адрес слоя подписок, затем relay (и наоборот).
     * Пустой список или одна заглушка 127.0.0.1 («ключ не принят») — пробуем следующий адрес.
     */
    private fun applySubAny(url: String): JSONObject {
        var last: JSONObject? = null
        for (u in ScLock.subCandidates(url)) {
            val r = runCatching { applySub(u) }.getOrElse {
                JSONObject().put("type", "sub").put("ok", false).put("error", it.message ?: "Ошибка")
            }
            if (r.optBoolean("ok") && !placeholderOnly()) {
                ScSecureStore.saveKey(this, u)
                return r
            }
            if (r.optBoolean("ok")) r.put("ok", false).put("error", "Сервер не принял ключ — проверь лимит устройств")
            last = r
        }
        return last ?: applySub(url)
    }

    private fun placeholderOnly(): Boolean {
        val sub = ourSub() ?: return true
        val list = MmkvManager.decodeServerList(sub.guid)
        return list.isNotEmpty() && list.all { MmkvManager.decodeServerConfig(it)?.server == "127.0.0.1" }
    }

    // ───────────── 1.1.0: вход, статус, «Северное Сияние (Авто)» ─────────────

    private fun hasSubNow(): Boolean = ourSub()?.let { MmkvManager.decodeServerList(it.guid).isNotEmpty() } ?: false

    private fun serverCount(): Int = ourSub()?.let { MmkvManager.decodeServerList(it.guid).size } ?: 0

    private fun autoMode(): Boolean = prefs.getBoolean("auto", true)

    /** Сервер выдал (новый) ключ этого устройства — ставим его. */
    private fun applyActiveLink(st: JSONObject?) {
        if (st?.optString("state") != "active") return
        val link = st.optString("link")
        val subscription = ourSub()?.subscription
        if (ScLock.isAllowedSubUrl(link) &&
            (ScLock.needsSubUpdate(subscription?.url, link) || !subscription?.filter.isNullOrBlank())
        ) {
            applySubAny(link)
        }
    }

    /**
     * Токен кабинета (localStorage 'sc_tok') ↔ токен приложения.
     * Кабинет вошёл — приложение запоминает его вход; у кабинета входа нет, а у приложения есть — отдаём ему;
     * кабинет вышел после того, как знал наш токен, — выходит и приложение.
     */
    private fun syncToken() {
        if (!::web.isInitialized) return
        val url = web.url ?: return
        if (!allowedAuthorities.any { url.startsWith("https://$it/") }) return
        web.evaluateJavascript("(function(){try{return localStorage.getItem('sc_tok')||''}catch(e){return ''}})()") { raw ->
            val pageTok = runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }.getOrNull().orEmpty()
            val nat = account.token
            val dead = account.deadToken()
            val seen = secure.getString("tok_seen", "").orEmpty()
            // 1.2.6: источник истины — приложение. Раньше токен страницы всегда побеждал: после сброса ключа и входа
            // по новому страница отдавала СТАРЫЙ токен, приложение принимало его, получало 401 и стирало новый ключ — цикл.
            when {
                pageTok.isNotEmpty() && pageTok == nat -> secure.edit().putString("tok_seen", pageTok).apply()
                // страница хранит отозванный вход: отдаём ей актуальный или убираем
                pageTok.isNotEmpty() && pageTok == dead -> if (nat.isNotEmpty()) pushTokenToPage(nat) else clearPageToken()
                // страница отстала: токен тот же, что мы видели раньше, а у приложения уже другой (смена ключа, активация)
                pageTok.isNotEmpty() && nat.isNotEmpty() && pageTok == seen -> pushTokenToPage(nat)
                // на странице вошли сами (новый токен, которого мы не видели)
                pageTok.isNotEmpty() -> {
                    account.adopt(pageTok)
                    secure.edit().putString("tok_seen", pageTok).apply()
                }
                nat.isNotEmpty() -> {
                    // 1.2.8: пустой токен страницы НЕ повод выходить. Так бывает при открытии резервного адреса (другой origin,
                    // пустой localStorage), очистке хранилища WebView системой и т.п. Настоящий выход идёт явно (кнопка → logout())
                    // или через 401/403 от сервера (REVOKED). Возвращаем странице токен приложения; не чаще раза на токен за запуск,
                    // чтобы не уйти в цикл перезагрузок, если хранилище страницы не пишется.
                    if (restoredFor != nat) {
                        restoredFor = nat
                        pushTokenToPage(nat)
                    }
                }
            }
        }
    }

    /** Токен, который уже возвращали странице в этом запуске (защита от цикла перезагрузок). */
    private var restoredFor = ""

    private fun pushTokenToPage(nat: String) {
        secure.edit().putString("tok_seen", nat).apply()
        val js = JSONObject.quote(nat)
        web.evaluateJavascript("try{localStorage.setItem('sc_tok',$js);location.reload()}catch(e){}", null)
    }

    private fun clearPageToken() {
        secure.edit().remove("tok_seen").apply()
        web.evaluateJavascript("try{localStorage.removeItem('sc_tok');location.reload()}catch(e){}", null)
    }

    /**
     * 1.2.6: единый выход (кнопка, 401/403, кабинет вышел): VPN выключается, ключ, подписка и вход стираются,
     * страница очищается (localStorage, cookie, история), кабинет открывается заново на экране входа.
     */
    private fun fullLogout(reason: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { dropLocalKey() }
            account.signOut()
            runOnUiThread {
                runCatching { LauncherManager.stopService(this@ScActivity) }
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
                if (::web.isInitialized) {
                    runCatching { web.clearHistory(); web.clearCache(true) }
                    loadHome(PRIMARY_HOME)
                }
                emit(JSONObject().put("type", "state").put("state", "off"))
                emit(JSONObject().put("type", "toast").put("text", "Вы вышли из аккаунта ($reason). Войдите снова"))
            }
        }
    }

    /** Подключение: статус аккаунта → (новый ключ) → выбор сервера в «Авто» → запуск. */
    private fun connectFlow() {
        if (preparing) return
        preparing = true
        emit(JSONObject().put("type", "state").put("state", "connecting"))
        lifecycleScope.launch(Dispatchers.IO) {
            var go = true
            try {
                val st = runCatching { account.status() }.getOrNull()
                // Сеть/5xx/таймаут (Outcome.NETWORK) ключ не трогают — подключаемся с тем, что есть.
                val out = account.outcome
                if (out == ScAccount.Outcome.LOCKED || out == ScAccount.Outcome.REVOKED || out == ScAccount.Outcome.REBIND) {
                    go = false
                    if (out == ScAccount.Outcome.REVOKED) fullLogout("сессия закончилась")      // DEVICE_LOCKED/REBIND: ключ не отозван, не трогаем
                    else {      // 1.2.7: 409 DEVICE_LOCKED — в фоновую телеметрию (не чаще раза в 10 минут)
                        ScTelemetry.log("connect: key is bound to another device")
                        ScTelemetry.report(this@ScActivity, ScTelemetryGate.DEVICE_LOCKED, ScTelemetry.locationOf(MmkvManager.getSelectServer()))
                    }
                    val msg = if (out != ScAccount.Outcome.REVOKED) "Ключ привязан к другому устройству" else "Ключ больше не действует — введи код активации"
                    emit(JSONObject().put("type", "account").put("state", if (out != ScAccount.Outcome.REVOKED) "device_locked" else "no_account").put("message", msg))
                    emit(JSONObject().put("type", "state").put("state", "error").put("error", msg))
                    openActivation()      // при блокировке экран сам покажет таймер
                } else if (st == null && account.token.isEmpty() && !hasSubNow()) {
                    go = false
                    emit(JSONObject().put("type", "state").put("state", "off"))
                    openActivation()
                } else if (st != null) {
                    val state = st.optString("state")
                    if (state in ScAccount.BAD_STATES) {
                        go = false
                        val msg = st.optString("message").ifEmpty { "Нет активной подписки" }
                        emit(JSONObject().put("type", "account").put("state", state).put("message", msg))
                        emit(JSONObject().put("type", "state").put("state", "error").put("error", msg))
                        if (state == "expired") runOnUiThread {
                            if (::web.isInitialized) web.evaluateJavascript("try{go('plans')}catch(e){}", null)
                        }
                    } else {
                        runCatching { applyActiveLink(st) }
                    }
                }
                if (go && !hasSubNow()) runCatching { restoreKeyIfMissing() }
                if (go && autoMode()) runCatching { pickAuto() }
            } finally {
                preparing = false
            }
            if (go) runOnUiThread { requestStart() }
        }
    }

    /**
     * «Северное Сияние»: реальная проверка всех серверов подписки через ядро Xray (запрос через туннель).
     * Побеждает самый быстрый; при почти равной скорости — тот, что выше в подписке (сервер ставит первыми свободные ноды).
     */
    /** smart_v1: реальная задержка до каждого сервера через ядро Xray, параллельно, не дольше 10 с. */
    private suspend fun measureAll(list: List<String>): Map<Int, Long> {
        runCatching { CoreNativeManager.initCoreEnv(applicationContext) }
        val url = SettingsManager.getDelayTestUrl()
        val res = ConcurrentHashMap<Int, Long>()
        val jobs = list.mapIndexed { i, g ->
            lifecycleScope.async(Dispatchers.IO) {
                val cfg = CoreConfigManager.getV2rayConfig4Speedtest(this@ScActivity, g)
                res[i] = if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, url) else -1L
            }
        }
        withTimeoutOrNull(10_000L) { jobs.awaitAll() }
        pings.clear()
        list.forEachIndexed { i, g -> pings[g] = res[i] ?: -1L }
        return res
    }

    /** Нода сервера из рейтинга /nodes/rank: ищем по ключу/имени в названии профиля. null — не опознали. */
    private fun nodeOf(rank: ScAccount.Rank?, guid: String): ScAccount.Node? {
        val title = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
        if (rank == null || title.isEmpty()) return null
        return rank.nodes.firstOrNull { (it.name.isNotEmpty() && title.contains(it.name, true)) || (it.key.isNotEmpty() && title.contains(it.key, true)) }
    }

    @Volatile
    private var connectTimeoutMs = 6000L

    /** Серверы, уже опробованные в текущей попытке подключения (для автопереключения). */
    private val triedServers = HashSet<String>()

    private suspend fun pickAuto() {
        val sub = ourSub() ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.size < 2) return
        // 1) рейтинг сервера (нагрузка/доступность); нет ответа — просто пинг-проба ниже
        val rank = runCatching { account.nodesRank() }.getOrNull()
        rank?.let { connectTimeoutMs = it.connectTimeoutMs }
        // 2) пинг-проба через ядро; перегруженные и недоступные ноды отсеиваем, если остались другие
        val res = measureAll(list)
        var ok = list.indices.filter { (res[it] ?: -1L) > 0L }
        if (ok.isEmpty()) return   // никто не ответил — скорее всего, нет интернета; оставляем прежний сервер
        ok.filter { nodeOf(rank, list[it])?.available != false }.takeIf { it.isNotEmpty() }?.let { ok = it }
        val best = ok.minOf { res[it]!! }
        val slack = maxOf(20L, best * 15 / 100)
        val pick = ok.filter { res[it]!! <= best + slack }
            .minWithOrNull(compareBy({ nodeOf(rank, list[it])?.rank ?: 99 }, { it })) ?: return
        MmkvManager.setSelectServer(list[pick])
    }

    private fun measureOne(guid: String): Long {
        runCatching { CoreNativeManager.initCoreEnv(applicationContext) }
        val cfg = CoreConfigManager.getV2rayConfig4Speedtest(this, guid)
        return if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, SettingsManager.getDelayTestUrl()) else -1L
    }

    /**
     * Таймаут коннекта: ядро не поднялось за connect_timeout_ms или сервер не отвечает через туннель —
     * переходим на следующий по рейтингу сервер (по одному разу каждый), не трогая ключ и токен.
     */
    private fun watchConnect() {
        if (!autoMode()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val limit = connectTimeoutMs
            val t0 = System.currentTimeMillis()
            while (!running && System.currentTimeMillis() - t0 < limit) kotlinx.coroutines.delay(300)
            val sel = MmkvManager.getSelectServer() ?: return@launch
            val alive = running && (withTimeoutOrNull(limit) { measureOne(sel) } ?: -1L) > 0L
            if (alive) { triedServers.clear(); return@launch }
            failoverNext(sel)
        }
    }

    private suspend fun failoverNext(failed: String) {
        val sub = ourSub() ?: return
        triedServers.add(failed)
        val list = MmkvManager.decodeServerList(sub.guid).filter { it !in triedServers }
        if (list.isEmpty()) {       // все опробованы — оставляем как есть
            triedServers.clear()
            ScTelemetry.log("connect: all servers tried, none answers")
            ScTelemetry.report(this, ScTelemetryGate.TUNNEL_TIMEOUT, ScTelemetry.locationOf(failed))      // 1.2.7: фон
            return
        }
        val rank = runCatching { account.nodesRank() }.getOrNull()
        val next = list.withIndex().minWithOrNull(
            compareBy({ if (nodeOf(rank, it.value)?.available == false) 1 else 0 }, { nodeOf(rank, it.value)?.rank ?: 99 }, { it.index })
        )?.value ?: return
        MmkvManager.setSelectServer(next)
        emit(JSONObject().put("type", "toast").put("text", "Сервер не отвечает — переключаюсь на резервный"))
        runOnUiThread { LauncherManager.restartServiceOrStart(this) { LauncherManager.startService(this) } }
        watchConnect()
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
            triedServers.clear()
            watchConnect()
        }
    }

    /**
     * apk_v5: «Приложения без VPN». Включаем режим обхода v2rayNG и добавляем установленные
     * российские приложения из RU_DIRECT (кроме тех, что клиент сам снял).
     */
    private fun launcherApps(): List<android.content.pm.ResolveInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(launcher, 0)
    }

    private fun launcherPkgs(): Set<String> =
        launcherApps().mapNotNull { it.activityInfo?.packageName }.filter { it != BuildConfig.APPLICATION_ID }.toSet()

    /**
     * apk_v6: всё напрямую, кроме приложений из NEED_VPN (и тех, что клиент сам отправил через VPN).
     * Новые установленные приложения тоже идут напрямую. Плюс маршруты: российские сайты напрямую.
     */
    private fun syncDirectApps(firstRun: Boolean) {
        runCatching {
            if (firstRun) {
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
                MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, mutableSetOf<String>())
                prefs.edit().remove("direct_removed").apply()
                SettingsManager.resetRoutingRulesets(RU_ROUTING)
                prefs.edit().putBoolean("defaults_v6", true).apply()
            }
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false) != true) return
            val viaVpn = prefs.getStringSet("direct_removed", emptySet()) ?: emptySet()
            val set = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toMutableSet() ?: mutableSetOf()
            val before = set.size
            launcherPkgs().filter { it !in NEED_VPN && it !in viaVpn && it !in set }.forEach { set.add(it) }
            if (set.size != before || firstRun) MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, set)
        }
    }

    private fun iconData(pkg: String): String = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun appsJson(): JSONObject {
        val pm = packageManager
        val on = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false) == true &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false) == true
        val set = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf()
        val acts = launcherApps()
        val seen = HashSet<String>()
        val arr = JSONArray()
        acts.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == BuildConfig.APPLICATION_ID || !seen.add(pkg)) return@mapNotNull null
            Triple(pkg, ri.loadLabel(pm).toString(), pkg in RU_DIRECT)
        }.sortedWith(compareBy<Triple<String, String, Boolean>>({ it.first !in set }, { !it.third }, { it.second.lowercase() }))
            .forEach { (pkg, name, rec) ->
                arr.put(JSONObject().put("pkg", pkg).put("name", name).put("sel", pkg in set)
                    .put("rec", rec).put("vpn", pkg in NEED_VPN).put("icon", iconData(pkg)))
            }
        return JSONObject().put("type", "apps").put("on", on).put("list", arr)
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

    // ── upd_v1: обновление приложения изнутри ──
    @Volatile private var updBusy = false
    private var updFile: java.io.File? = null
    private var updPending = false

    private fun updEmit(stage: String, pct: Int = 0, text: String = "") {
        emit(JSONObject().put("type", "update").put("stage", stage).put("pct", pct).put("text", text))
        runOnUiThread { updUi(stage, pct, text) }
    }

    // ── upd_v2: проверка version.json при запуске (не зависит от входа в кабинет) ──
    private var updDialog: android.app.AlertDialog? = null
    private var updBar: android.widget.ProgressBar? = null
    private var updMsg: android.widget.TextView? = null
    private var updChecked = 0L
    private var updAskedFor = 0

    private fun updCheckNative() {
        if (updBusy || System.currentTimeMillis() - updChecked < 30 * 60_000L) return
        updChecked = System.currentTimeMillis()
        Thread {
            runCatching {
                val c = java.net.URL("https://sibirapp.duckdns.org/app/dl/version.json?t=${System.currentTimeMillis()}")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Cache-Control", "no-cache")
                if (c.responseCode != 200) return@runCatching
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                // versionCode на сервере — с префиксом ABI (4000790), у BuildConfig — чистый (790)
                val srv = (j.optLong("versionCode", 0) % 1_000_000L).toInt()
                if (srv <= BuildConfig.VERSION_CODE) { updCleanup(); return@runCatching }
                if (srv == updAskedFor) return@runCatching   // уже спрашивали в этом запуске
                updAskedFor = srv
                val v = j.optString("version"); val mb = (j.optLong("size", 0) / 1048576).toInt()
                val notes = j.optString("notes")
                runOnUiThread { if (!isFinishing) updAsk(v, mb, notes) }
            }
        }.start()
    }

    private fun updAsk(v: String, mb: Int, notes: String) {
        updFile = null
        android.app.AlertDialog.Builder(this)
            .setTitle("Доступно обновление $v")
            .setMessage((if (notes.isNotBlank()) notes + "\n\n" else "") + "Скачаем" + (if (mb > 0) " ($mb МБ)" else "") + " и откроем установщик Android. Вход и настройки сохранятся.")
            .setPositiveButton("Обновить") { _, _ -> updShowProgress(); updRun() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun updShowProgress() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        updMsg = android.widget.TextView(this).apply { text = "Подключаюсь…" }
        updBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(updMsg); box.addView(updBar)
        updDialog = android.app.AlertDialog.Builder(this).setTitle("Обновление").setView(box)
            .setNegativeButton("Скрыть", null).show()
    }

    private fun updUi(stage: String, pct: Int, text: String) {
        val d = updDialog ?: return
        when (stage) {
            "download" -> { updBar?.progress = pct; updMsg?.text = "Скачиваю… $pct%" }
            "perm" -> updMsg?.text = "Разреши установку для Siberia Connect в настройках и вернись — установка продолжится сама"
            "install" -> { updBar?.progress = 100; updMsg?.text = "Подтверди установку в окне Android" }
            "error" -> { updMsg?.text = text; d.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "Повторить") { _, _ -> updShowProgress(); updRun() } }
        }
    }

    /** Удаляет скачанные ранее APK (старая папка upd тоже). */
    private fun updCleanup() {
        runCatching {
            java.io.File(cacheDir, "updates").deleteRecursively()
            java.io.File(cacheDir, "upd").deleteRecursively()
        }
    }

    private fun updCanInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun updInstall(f: java.io.File) {
        if (!updCanInstall()) {
            updPending = true
            updEmit("perm")
            runOnUiThread {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            }
            return
        }
        runOnUiThread {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(this@ScActivity, "$packageName.scupdate", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                updEmit("install")
            }.onFailure { updEmit("error", text = "Не удалось открыть установщик Android") }
        }
    }

    private fun updRun() {
        updFile?.let { if (it.exists()) { updInstall(it); return } }
        if (updBusy) return
        updBusy = true
        Thread {
            try {
                updCleanup()
                val dir = java.io.File(cacheDir, "updates").apply { mkdirs() }
                val tmp = java.io.File(dir, "dl.part")
                val c = java.net.URL("https://sibirapp.duckdns.org/app/dl/SiberiaConnect.apk?t=${System.currentTimeMillis()}").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("Cache-Control", "no-cache")
                if (c.responseCode != 200) throw Exception("сервер ответил ${c.responseCode}")
                val total = c.contentLength.toLong()
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; updEmit("download", p) }
                            }
                        }
                    }
                }
                // это точно наше приложение; подпись при установке Android сверит сам — чужой APK поверх не встанет
                if (packageManager.getPackageArchiveInfo(tmp.path, 0)?.packageName != packageName) {
                    tmp.delete()
                    throw Exception("файл обновления повреждён")
                }
                val f = java.io.File(dir, "SiberiaConnect.apk")
                f.delete()
                if (!tmp.renameTo(f)) throw Exception("не удалось сохранить файл")
                updFile = f
                updInstall(f)
            } catch (e: Exception) {
                updEmit("error", text = "Не удалось скачать обновление: " + (e.message ?: "проверь интернет"))
            } finally {
                updBusy = false
            }
        }.start()
    }

    // ── link_v1: вход по ссылке из бота ──
    // unified_v1: siberiaconnect://auth?token=… (и старый login?c=…) — одноразовый вход «Подключить в 1 клик»
    private fun dlCode(i: Intent?): String? {
        val d = i?.data ?: return null
        if (d.scheme != "siberiaconnect" || (d.host != "login" && d.host != "auth")) return null
        val c = d.getQueryParameter("token") ?: d.getQueryParameter("c") ?: return null
        return c.takeIf { Regex("^[A-Za-z0-9_-]{8,64}$").matches(it) }
    }

    /** Входа ещё нет — кабинет входит сразу (dla); есть другой аккаунт — спросит «Войти как …?» (dl). */
    private fun dlUrl(i: Intent?): String? {
        val c = dlCode(i) ?: return null
        return if (account.replaceable) "$PRIMARY_HOME&dla=$c" else "$PRIMARY_HOME&dl=$c"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = dlUrl(intent) ?: return
        if (::web.isInitialized) loadHome(u)
    }

    inner class Bridge {
        /** Кабинет может открыть экран ввода кода активации / блокировки с таймером. */
        @JavascriptInterface
        fun openActivation() = this@ScActivity.openActivation()


        /** Адрес, по которому страница берёт файлы анимации логотипа (их отдаёт приложение, см. ScWolfAssets). */
        @JavascriptInterface
        fun assetBase(): String = ScWolfAssets.BASE

        @JavascriptInterface
        fun wolfMediaVersion(): Int = 1

        /** Тактильный отклик в момент начала подключения. */
        @JavascriptInterface
        fun haptic() {
            runOnUiThread { if (::web.isInitialized) web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }

        @JavascriptInterface
        fun apiFallback(): String =
            if (FALLBACK_HOST.isBlank() || FALLBACK_HOST == PRIMARY_HOST) "" else "https://$FALLBACK_HOST/app/"

        @JavascriptInterface
        fun pasteText(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return runCatching {
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@ScActivity)?.toString().orEmpty()
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun copyText(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Siberia Connect", text))
        }

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
                    applySubAny(url)
                } catch (e: Exception) {
                    JSONObject().put("type", "sub").put("ok", false).put("error", e.message ?: "Ошибка")
                }
                emit(r)
            }
        }

        @JavascriptInterface
        fun connect() {
            connectFlow()   // 1.1.0: статус → ключ → «Авто» → запуск
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { LauncherManager.stopService(this@ScActivity) }
        }

        @JavascriptInterface
        fun servers(): String {
            val sub = ourSub() ?: return "[]"
            val sel = MmkvManager.getSelectServer()
            val list = MmkvManager.decodeServerList(sub.guid)
            val auto = autoMode() && list.size > 1
            val arr = JSONArray()
            if (list.size > 1) {
                // 1.1.0: «Северное Сияние (Авто)» первым пунктом — кабинет показывает его как обычный сервер
                val activeName = if (running && sel != null) MmkvManager.decodeServerConfig(sel)?.remarks else null
                arr.put(JSONObject().put("guid", "auto").put("auto", true).put("selected", auto)
                    .put("name", if (auto && activeName != null) "⚡ Автовыбор → $activeName" else "⚡ Автовыбор · самый быстрый сервер"))
            }
            list.forEach { g ->
                val p = MmkvManager.decodeServerConfig(g) ?: return@forEach
                arr.put(JSONObject().put("guid", g).put("name", p.remarks).put("selected", !auto && g == sel)
                    .put("active", running && g == sel).put("ping", pings[g] ?: 0L))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun select(guid: String) {
            if (guid == "auto") {
                prefs.edit().putBoolean("auto", true).apply()
                if (running) lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { pickAuto() }
                    runOnUiThread { LauncherManager.restartService(this@ScActivity) }
                }
                return
            }
            prefs.edit().putBoolean("auto", false).apply()
            runOnUiThread {
                val was = MmkvManager.getSelectServer()
                MmkvManager.setSelectServer(guid)
                if (running && was != guid) LauncherManager.restartService(this@ScActivity)
            }
        }

        /** 1.1.0 */
        @JavascriptInterface
        fun autoMode(): Boolean = this@ScActivity.autoMode()

        /** smart_v1: замерить задержку до всех серверов сейчас (без подключения) → событие {type:"pings"} */
        @JavascriptInterface
        fun pingAll() {
            if (pinging) return
            pinging = true
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val list = ourSub()?.let { MmkvManager.decodeServerList(it.guid) }.orEmpty()
                    if (list.isNotEmpty()) measureAll(list) else emptyMap()
                }
                pinging = false
                emit(JSONObject().put("type", "pings"))
            }
        }

        /** smart_v1: российские сайты и адреса напрямую, мимо VPN (по умолчанию включено) */
        @JavascriptInterface
        fun ruDirect(): Boolean = prefs.getBoolean("ru_direct", true)

        @JavascriptInterface
        fun setRuDirect(on: Boolean) {
            prefs.edit().putBoolean("ru_direct", on).apply()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { SettingsManager.resetRoutingRulesets(if (on) RU_ROUTING else BASE_ROUTING) }
                if (running) runOnUiThread { LauncherManager.restartService(this@ScActivity) }
            }
        }

        @JavascriptInterface
        fun setAutoMode(on: Boolean) {
            if (on) select("auto") else prefs.edit().putBoolean("auto", false).apply()
        }

        @JavascriptInterface
        fun account(): String = this@ScActivity.account.last?.optString("state").orEmpty()

        @JavascriptInterface
        fun linkTelegram() {
            lifecycleScope.launch(Dispatchers.IO) {
                val (code, info) = this@ScActivity.account.linkCode()
                if (code == null) {
                    emit(JSONObject().put("type", "toast").put("text", info))
                    return@launch
                }
                if (info.isNotEmpty()) runOnUiThread { this@ScActivity.openExternal(info) }
                emit(JSONObject().put("type", "toast").put("text", "Открыл бота. Если не открылся — отправь ему /link $code"))
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { this@ScActivity.openExternal(url) }
        }

        /** 1.2.6: выйти из аккаунта полностью (кнопка в кабинете). */
        @JavascriptInterface
        fun logout() = this@ScActivity.fullLogout("вы вышли")

        @JavascriptInterface
        fun loggedIn(): Boolean = this@ScActivity.account.token.isNotEmpty()

        /** 1.2.6: «Подключить телевизор» — код с экрана ТВ (8 цифр, можно с дефисом). Ответ — событие toast. */
        @JavascriptInterface
        fun tvApprove(code: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val err = this@ScActivity.account.tvApprove(code)
                emit(JSONObject().put("type", "toast").put("text", err ?: "✅ Телевизор подключён — через пару секунд он войдёт сам"))
            }
        }

        /** 1.2.6: окно «Ключ, код или номер» (то же, что при блокировке/активации). */
        @JavascriptInterface
        fun redeemDialog() = this@ScActivity.openActivation()

        /** apk_v4: трафик приложения (весь VPN идёт через него) и время подключения */
        @JavascriptInterface
        fun stats(): String {
            val uid = android.os.Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            return JSONObject().put("rx", rx).put("tx", tx).put("since", prefs.getLong("since", 0L))
                .put("now", System.currentTimeMillis()).toString()
        }

        /** apk_v5: приложения без VPN */
        @JavascriptInterface
        fun directInfo(): String {
            val on = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false) == true &&
                MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false) == true
            val n = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.size ?: 0
            return JSONObject().put("on", on).put("count", n).toString()
        }

        @JavascriptInterface
        fun loadApps() {
            lifecycleScope.launch(Dispatchers.IO) {
                val r = try { appsJson() } catch (e: Exception) {
                    JSONObject().put("type", "apps").put("error", e.message ?: "Ошибка")
                }
                emit(r)
            }
        }

        @JavascriptInterface
        fun setDirect(on: Boolean, pkgsJson: String) {
            val arr = runCatching { JSONArray(pkgsJson) }.getOrDefault(JSONArray())
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
            val removed = launcherPkgs().filter { it !in set }.toSet()
            prefs.edit().putStringSet("direct_removed", removed).apply()
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, on)
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, set)
            if (running) runOnUiThread { LauncherManager.restartService(this@ScActivity) }
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
        fun installUpdate() = updRun()

        @JavascriptInterface
        fun advanced() {
            // Siberia Connect: вместо экрана v2rayNG (там импорт чужих серверов) — только настройки
            runOnUiThread {
                startActivity(Intent(this@ScActivity, com.v2ray.ang.ui.settings.SettingsActivity::class.java))
            }
        }
    }
}
