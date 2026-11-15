package com.v2ray.ang.ui.sc

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.*
import com.v2ray.ang.core.*
import com.v2ray.ang.dto.entities.*
import com.v2ray.ang.handler.*
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ScTvActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("sc_prefs", Context.MODE_PRIVATE) }
    private val account by lazy { ScAccount(prefs) { devId() } }
    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var updBusy = false
    private var updFile: File? = null
    private var updExpectedSha: String? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvSubInfo: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnServers: Button
    private lateinit var btnLinkTg: Button
    private lateinit var btnUpdate: Button
    private lateinit var tvLinkBox: TextView
    private lateinit var serverListContainer: LinearLayout

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) startCore() else { busy = false; refreshUi() }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> { running = true; busy = false; refreshUi() }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS, AppConfig.MSG_STATE_START_FAILURE -> { running = false; busy = false; refreshUi() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildTvLayout()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { LauncherManager.sendMsgToService(this, AppConfig.MSG_REGISTER_CLIENT, "") }
        refreshUi()
        bootstrapAccountAndSub()
        checkAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        runCatching { LauncherManager.sendMsgToService(this, AppConfig.MSG_REGISTER_CLIENT, "") }
        refreshUi()
        updFile?.let { if (it.exists() && updCanInstall()) installApk(it) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    private fun devId(): String {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return MessageDigest.getInstance("SHA-256").digest(("sc-tv-$raw").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun ourSub(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == "Siberia Connect" }

    private fun buildTvLayout() {
        val root = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B1120")); isFillViewport = true }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(32), dp(48), dp(32)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "SIBERIA CONNECT TV"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ver = TextView(this).apply { text = "v${BuildConfig.VERSION_NAME}"; textSize = 15f; setTextColor(Color.parseColor("#94A3B8")) }
        header.addView(title); header.addView(ver); page.addView(header)

        btnUpdate = makeTvButton("ДОСТУПНО ОБНОВЛЕНИЕ — НАЖМИТЕ ОК", "#D97706", "#F59E0B").apply {
            visibility = View.GONE; setOnClickListener { downloadAndInstallUpdate() }
        }
        page.addView(btnUpdate, marginParams(16))

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = marginParams(24) }
        val leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(28), dp(28), dp(28)); background = cardBg("#111827")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f).apply { marginEnd = dp(16) }
        }
        tvStatus = TextView(this).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        tvServerInfo = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#CBD5E1")); layoutParams = marginParams(10) }
        btnConnect = makeTvButton("ПОДКЛЮЧИТЬ VPN", "#0284C7", "#38BDF8").apply {
            textSize = 20f; setPadding(dp(24), dp(20), dp(24), dp(20)); setOnClickListener { toggleVpn() }
        }
        btnServers = makeTvButton("ВЫБРАТЬ СЕРВЕР ВРУЧНУЮ", "#1E293B", "#334155").apply { setOnClickListener { toggleServerList() } }
        serverListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutParams = marginParams(12) }

        leftCard.addView(tvStatus); leftCard.addView(tvServerInfo)
        leftCard.addView(btnConnect, marginParams(24)); leftCard.addView(btnServers, marginParams(14)); leftCard.addView(serverListContainer)

        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(28), dp(28), dp(28)); background = cardBg("#111827")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply { marginStart = dp(16) }
        }
        val accHeader = TextView(this).apply { text = "АККАУНТ И ПОДПИСКА"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#38BDF8")) }
        tvSubInfo = TextView(this).apply { text = "Проверка статуса..."; textSize = 17f; setTextColor(Color.WHITE); layoutParams = marginParams(12) }
        btnLinkTg = makeTvButton("ПРИВЯЗАТЬ TELEGRAM (КОД)", "#1E293B", "#334155").apply { setOnClickListener { requestTelegramLinkCode() } }
        val btnRefreshSub = makeTvButton("ОБНОВИТЬ ПОДПИСКУ И СЕРВЕРЫ", "#1E293B", "#334155").apply {
            setOnClickListener { tvSubInfo.text = "Обновляем ключи..."; bootstrapAccountAndSub(true) }
        }
        tvLinkBox = TextView(this).apply {
            visibility = View.GONE; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FDE047"))
            setPadding(dp(16), dp(16), dp(16), dp(16)); background = cardBg("#1E293B"); layoutParams = marginParams(16)
        }
        rightCard.addView(accHeader); rightCard.addView(tvSubInfo)
        rightCard.addView(btnLinkTg, marginParams(20)); rightCard.addView(btnRefreshSub, marginParams(12)); rightCard.addView(tvLinkBox)

        cols.addView(leftCard); cols.addView(rightCard); page.addView(cols); root.addView(page)
        setContentView(root)
        btnConnect.post { btnConnect.requestFocus() }
    }

    private fun makeTvButton(label: String, normalHex: String, focusHex: String): Button = Button(this).apply {
        text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        isFocusable = true; isFocusableInTouchMode = true; setPadding(dp(20), dp(14), dp(20), dp(14)); background = cardBg(normalHex)
        setOnFocusChangeListener { v, f ->
            v.background = cardBg(if (f) focusHex else normalHex, f)
            v.scaleX = if (f) 1.03f else 1.0f; v.scaleY = if (f) 1.03f else 1.0f
        }
    }

    private fun cardBg(hex: String, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(Color.parseColor(hex)); cornerRadius = dp(12).toFloat()
        if (stroke) setStroke(dp(3), Color.WHITE)
    }

    private fun marginParams(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshUi() = runOnUiThread {
        when {
            busy -> { tvStatus.text = "● ПОДКЛЮЧЕНИЕ..."; tvStatus.setTextColor(Color.parseColor("#FBBF24")); btnConnect.text = "ПОДОЖДИТЕ..." }
            running -> { tvStatus.text = "● VPN ПОДКЛЮЧЁН"; tvStatus.setTextColor(Color.parseColor("#4ADE80")); btnConnect.text = "ОТКЛЮЧИТЬ VPN" }
            else -> { tvStatus.text = "● VPN ВЫКЛЮЧЕН"; tvStatus.setTextColor(Color.parseColor("#F87171")); btnConnect.text = "ПОДКЛЮЧИТЬ VPN" }
        }
        val selGuid = MmkvManager.getSelectServer()
        val selProfile = if (!selGuid.isNullOrEmpty()) MmkvManager.decodeServerConfig(selGuid) else null
        val auto = prefs.getBoolean("auto", true)
        tvServerInfo.text = when {
            selProfile != null && auto -> "Локация: Авто (${selProfile.remarks})"
            selProfile != null -> "Локация: ${selProfile.remarks}"
            else -> "Локация: Автоподбор (Северное Сияние)"
        }
    }

    private fun bootstrapAccountAndSub(forceSub: Boolean = false) = lifecycleScope.launch(Dispatchers.IO) {
        val hasSub = ourSub()?.let { MmkvManager.decodeServerList(it.guid).isNotEmpty() } ?: false
        if (account.needsAutoRegister(hasSub)) account.autoRegister()
        val st = account.status()
        val msg = st?.optString("message").orEmpty()
        val link = st?.optString("link").orEmpty()
        runOnUiThread { tvSubInfo.text = msg.ifEmpty { if (st?.optString("state") == "active") "Подписка активна" else "Пробный / гостевой доступ" } }
        if (link.isNotEmpty() && (forceSub || !hasSub)) {
            val existing = ourSub()
            val guid = existing?.guid ?: Utils.getUuid()
            val item = existing?.subscription ?: SubscriptionItem(remarks = "Siberia Connect", url = link)
            item.url = link; item.enabled = true
            MmkvManager.encodeSubscription(guid, item)
            SubscriptionUpdater.updateConfigViaSub(SubscriptionCache(guid, item))
            refreshUi()
        }
    }

    private fun requestTelegramLinkCode() {
        tvLinkBox.visibility = View.VISIBLE; tvLinkBox.text = "Генерируем код привязки..."
        lifecycleScope.launch(Dispatchers.IO) {
            if (account.token.isEmpty()) account.autoRegister()
            val (code, info) = account.linkCode()
            runOnUiThread {
                tvLinkBox.text = if (!code.isNullOrEmpty()) "Код для Telegram-бота: $code\nОткройте @SibirConnectBot → Профиль → Привязать устройство" else "Ошибка: $info"
            }
        }
    }

    private fun toggleVpn() {
        if (busy) return
        if (running) { busy = true; refreshUi(); LauncherManager.stopService(this); return }
        busy = true; refreshUi()
        lifecycleScope.launch(Dispatchers.IO) {
            val st = account.status()
            if (st != null && st.optString("state") in ScAccount.BAD_STATES) {
                busy = false; runOnUiThread { tvSubInfo.text = st.optString("message").ifEmpty { "Подписка неактивна" }; refreshUi() }; return@launch
            }
            if (prefs.getBoolean("auto", true)) pickAutoServer()
            runOnUiThread {
                val sel = MmkvManager.getSelectServer()
                if (sel.isNullOrEmpty()) { busy = false; tvStatus.text = "● НЕТ СЕРВЕРОВ (ОБНОВИТЕ ПОДПИСКУ)"; return@runOnUiThread }
                if (!SettingsManager.isVpnMode()) startCore()
                else {
                    val intent = VpnService.prepare(this@ScTvActivity)
                    if (intent == null) startCore() else vpnPermission.launch(intent)
                }
            }
        }
    }

    private suspend fun pickAutoServer() {
        val sub = ourSub() ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.isEmpty()) return
        if (list.size == 1) { MmkvManager.setSelectServer(list); return }
        runCatching { CoreNativeManager.initCoreEnv(applicationContext) }
        val url = SettingsManager.getDelayTestUrl()
        val res = ConcurrentHashMap<Int, Long>()
        val jobs = list.mapIndexed { i, g ->
            lifecycleScope.async(Dispatchers.IO) {
                val cfg = CoreConfigManager.getV2rayConfig4Speedtest(this@ScTvActivity, g)
                res[i] = if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, url) else -1L
            }
        }
        withTimeoutOrNull(10_000L) { jobs.awaitAll() }
        val ok = list.indices.filter { (res[it] ?: -1L) > 0L }
        if (ok.isEmpty()) { if (MmkvManager.getSelectServer().isNullOrEmpty()) MmkvManager.setSelectServer(list); return }
        val best = ok.minOf { res[it]!! }
        val pick = ok.filter { res[it]!! <= best + maxOf(20L, best * 15 / 100) }.minOrNull() ?: return
        MmkvManager.setSelectServer(list[pick])
    }

    private fun startCore() {
        prefs.edit().remove("since").apply()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = Utils.userAssetPath(this@ScTvActivity)
                for (name in listOf("geosite.dat", "geoip.dat")) {
                    val f = File(dir, name)
                    if (!f.exists() || f.length() == 0L) assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            runOnUiThread { LauncherManager.startService(this@ScTvActivity) }
        }
    }

    private fun toggleServerList() {
        if (serverListContainer.visibility == View.VISIBLE) { serverListContainer.visibility = View.GONE; return }
        serverListContainer.removeAllViews()
        serverListContainer.addView(makeTvButton("⚡ Автоподбор лучшего сервера", "#0F172A", "#0284C7").apply {
            setOnClickListener { prefs.edit().putBoolean("auto", true).apply(); serverListContainer.visibility = View.GONE; refreshUi() }
        }, marginParams(6))
        val sub = ourSub()
        val guids = if (sub != null) MmkvManager.decodeServerList(sub.guid) else emptyList()
        for (g in guids) {
            val cfg = MmkvManager.decodeServerConfig(g) ?: continue
            serverListContainer.addView(makeTvButton("🌐 ${cfg.remarks}", "#0F172A", "#0284C7").apply {
                setOnClickListener { prefs.edit().putBoolean("auto", false).apply(); MmkvManager.setSelectServer(g); serverListContainer.visibility = View.GONE; refreshUi() }
            }, marginParams(6))
        }
        serverListContainer.visibility = View.VISIBLE
    }

    private fun checkAppUpdate() = lifecycleScope.launch(Dispatchers.IO) {
        runCatching {
            val c = URL("https://" + ScActivity.PRIMARY_HOST + "/app/dl/version.json").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000; c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) return@runCatching
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val remoteVer = json.optString("version").trim()
            val sha = json.optString("sha256").trim()
            if (remoteVer.isNotEmpty() && isNewerVersion(remoteVer, BuildConfig.VERSION_NAME)) {
                updExpectedSha = sha
                runOnUiThread { btnUpdate.text = "ДОСТУПНА НОВАЯ ВЕРСИЯ $remoteVer — НАЖМИТЕ ОК ДЛЯ ОБНОВЛЕНИЯ"; btnUpdate.visibility = View.VISIBLE }
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun updCanInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun installApk(f: File) {
        if (!updCanInstall()) {
            runOnUiThread {
                btnUpdate.text = "РАЗРЕШИТЕ УСТАНОВКУ ИЗ ЭТОГО ИСТОЧНИКА И НАЖМИТЕ НАЗАД"
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            }
            return
        }
        runOnUiThread {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.scupdate", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { btnUpdate.text = "ОШИБКА ЗАПУСКА УСТАНОВЩИКА ANDROID" }
        }
    }

    private fun downloadAndInstallUpdate() {
        updFile?.let { if (it.exists()) { installApk(it); return } }
        if (updBusy) return
        updBusy = true; btnUpdate.text = "СКАЧИВАНИЕ ОБНОВЛЕНИЯ 0%..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(cacheDir, "upd").apply { mkdirs() }
                val tmp = File(dir, "tv_update.part")
                val outApk = File(dir, "SiberiaConnectTV.apk")
                val c = URL("https://" + ScActivity.PRIMARY_HOST + "/app/dl/SiberiaConnect.apk").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("Cache-Control", "no-cache")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLength.toLong()
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(65536); var done = 0L; var lastPct = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { btnUpdate.text = "СКАЧИВАНИЕ ОБНОВЛЕНИЯ $pct%..." } }
                        }
                    }
                }}
                val actualSha = md.digest().joinToString("") { "%02x".format(it) }
                val exp = updExpectedSha.orEmpty()
                if (exp.isNotEmpty() && !exp.equals(actualSha, true)) { tmp.delete(); throw Exception("SHA-256 mismatch") }
                if (outApk.exists()) outApk.delete()
                tmp.renameTo(outApk); updFile = outApk; installApk(outApk)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { btnUpdate.text = "ОШИБКА: ${e.message} (НАЖМИТЕ ДЛЯ ПОВТОРА)" }
            } finally { updBusy = false }
        }
    }
}
