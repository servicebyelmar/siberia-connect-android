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
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ScLock
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
    private val account by lazy { ScAccount(prefs) { devId() } }

    /** 1.1.0: «Северное Сияние (Авто)» — задержка последней проверки по каждому серверу, мс (-1 — не ответил) */
    private val pings = ConcurrentHashMap<String, Long>()

    @Volatile
    private var preparing = false
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
                    // 1.1.0: в «Авто» один раз за 3 минуты сами пробуем другой сервер
                    if (autoMode() && System.currentTimeMillis() - lastFailover > 180_000L && serverCount() > 1) {
                        lastFailover = System.currentTimeMillis()
                        emit(JSONObject().put("type", "toast").put("text", "Сервер не ответил — выбираю другой"))
                        connectFlow()
                        return
                    }
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
                val uri = request.url
                if ((uri.scheme == "https" || uri.scheme == "http") && uri.authority in allowedAuthorities) return false
                openExternal(uri.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (uri?.authority in allowedAuthorities && uri?.path?.startsWith("/app/") == true) {
                    homeLoaded = true
                    homeHandler.removeCallbacksAndMessages(null)
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
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        syncToken()
    }

    override fun onDestroy() {
        homeHandler.removeCallbacksAndMessages(null)
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
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == SUB_NAME && ScLock.isOurSub(it) }

    private fun applySub(url: String): JSONObject {
        val out = JSONObject().put("type", "sub")
        if (!ScLock.isAllowedSubUrl(url)) return out.put("ok", false).put("error", ScLock.DENIED_KEY)
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
            if (r.optBoolean("ok") && !placeholderOnly()) return r
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
        if (ScLock.isAllowedSubUrl(link) && link !in ScLock.subCandidates(ourSub()?.subscription?.url)) applySubAny(link)
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
            if (pageTok.isNotEmpty()) {
                if (pageTok != nat) account.adopt(pageTok)
                prefs.edit().putString("tok_seen", pageTok).apply()
            } else if (nat.isNotEmpty()) {
                if (prefs.getString("tok_seen", "") == nat) {
                    account.adopt("")   // кабинет знал этот вход и вышел — выходим и мы
                } else {
                    prefs.edit().putString("tok_seen", nat).apply()
                    val js = JSONObject.quote(nat)
                    web.evaluateJavascript("try{localStorage.setItem('sc_tok',$js);location.reload()}catch(e){}", null)
                }
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
                if (st != null) {
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
    private suspend fun pickAuto() {
        val sub = ourSub() ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.size < 2) return
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
        val ok = list.indices.filter { (res[it] ?: -1L) > 0L }
        if (ok.isEmpty()) return   // никто не ответил — скорее всего, нет интернета; оставляем прежний сервер
        val best = ok.minOf { res[it]!! }
        val slack = maxOf(20L, best * 15 / 100)
        val pick = ok.filter { res[it]!! <= best + slack }.minOrNull() ?: return
        MmkvManager.setSelectServer(list[pick])
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

    private fun updEmit(stage: String, pct: Int = 0, text: String = "") =
        emit(JSONObject().put("type", "update").put("stage", stage).put("pct", pct).put("text", text))

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
                val dir = java.io.File(cacheDir, "upd").apply { mkdirs() }
                val tmp = java.io.File(dir, "dl.part")
                val c = java.net.URL("https://sibirapp.duckdns.org/app/dl/SiberiaConnect.apk").openConnection() as java.net.HttpURLConnection
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
                    .put("name", if (auto && activeName != null) "⚡️ Северное Сияние → $activeName" else "⚡️ Северное Сияние (Авто)"))
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
