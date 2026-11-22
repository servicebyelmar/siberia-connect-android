package com.v2ray.ang.ui.sc

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.ui.compose.SiberiaArcticTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Siberia Connect TV (1.2.6): «зашёл → нажал → вышел». Без WebView, без форм ввода.
 *  • нет входа — крупный QR и короткий код: телефон подтверждает привязку (сайт или приложение), ТВ входит сам;
 *  • есть вход — статус, одна большая кнопка «Подключить», выбор локации (Авто или сервер), автозапуск, выход.
 * Всё управляется пультом (D-pad + ОК), у каждой кнопки заметная подсветка фокуса.
 */
class ScTvActivity : ComponentActivity() {
    private val secure by lazy { ScSecureStore.prefs(this) }
    private val did by lazy { ScSecureStore.deviceUuid(this) }
    private val account by lazy { ScAccount(secure) { did } }
    private val appPrefs by lazy { getSharedPreferences("sc_app", MODE_PRIVATE) }

    @Volatile private var running = false
    @Volatile private var busy = false
    private var updFile: File? = null
    private var updSha: String = ""

    private val authVm: ScActivationViewModel by viewModels()
    private lateinit var pairView: View
    private lateinit var mainView: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnLoc: Button
    private lateinit var btnBoot: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnLogout: Button
    private lateinit var wolf: ImageView
    private var wolfAudio: MediaPlayer? = null
    private val restoreWolf = Runnable {
        (wolf.drawable as? AnimationDrawable)?.stop()
        wolf.setImageResource(R.drawable.sc_tv_wolf)
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) startCore() else { busy = false; refreshUi() }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> { running = true; busy = false; refreshUi() }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> { running = false; busy = false; refreshUi() }
                AppConfig.MSG_STATE_START_FAILURE -> { running = false; busy = false; say("Не удалось запустить VPN. Попробуйте ещё раз"); refreshUi() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        firstRunDefaults()
        route()
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        updFile?.let { if (it.exists() && canInstall()) install(it) }
        if (account.token.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) { runCatching { recheckAccount() } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        super.onDestroy()
    }

    override fun onStop() {
        if (::wolf.isInitialized) {
            wolf.removeCallbacks(restoreWolf)
            restoreWolf.run()
        }
        releaseWolfAudio()
        super.onStop()
    }

    private fun releaseWolfAudio() {
        wolfAudio?.release()
        wolfAudio = null
    }

    private fun playWolf() {
        wolf.removeCallbacks(restoreWolf)
        restoreWolf.run()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) {
            wolf.setImageResource(R.drawable.sc_tv_wolf_motion)
            val motion = wolf.drawable as AnimationDrawable
            motion.start()
            wolf.postDelayed(restoreWolf, (0 until motion.numberOfFrames).sumOf { motion.getDuration(it).toLong() })
        }
        releaseWolfAudio()
        if (!appPrefs.getBoolean("wolf_sound", true)) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            wolfAudio = MediaPlayer.create(this, R.raw.sc_tv_wolf_howl, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            wolfAudio?.apply {
                setVolume(.75f, .75f)
                setOnCompletionListener { if (wolfAudio === it) releaseWolfAudio() }
                setOnErrorListener { player, what, extra ->
                    LogUtil.e(AppConfig.TAG, "TV wolf sound playback failed ($what, $extra)")
                    if (wolfAudio === player) releaseWolfAudio()
                    true
                }
                start()
            }
        } catch (error: IllegalStateException) {
            LogUtil.e(AppConfig.TAG, "TV wolf sound could not start", error)
            releaseWolfAudio()
        }
    }

    // ───────────── разметка ─────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(hex: String, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(Color.parseColor(hex)); cornerRadius = dp(14).toFloat()
        if (stroke) setStroke(dp(4), Color.parseColor("#A3F1FF"))
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun tvButton(label: String, normal: String, focused: String, size: Float = 24f, primary: Boolean = false): Button = Button(this).apply {
        text = label; textSize = size; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); isAllCaps = false
        minHeight = dp(64); isFocusable = true; isFocusableInTouchMode = true; setPadding(dp(24), dp(16), dp(24), dp(16)); background = bg(normal)
        if (primary) {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFBDF6FF.toInt(), 0xFF62DBF7.toInt()))
                .apply { cornerRadius = dp(14).toFloat() }
            setTextColor(0xFF061726.toInt())
        }
        setOnFocusChangeListener { v, f ->
            v.background = if (primary && !f) GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFBDF6FF.toInt(), 0xFF62DBF7.toInt())
            ).apply { cornerRadius = dp(14).toFloat() } else bg(if (f) focused else normal, f)
            if (primary) setTextColor(if (f) Color.WHITE else 0xFF061726.toInt())
            v.scaleX = if (f) 1.04f else 1f; v.scaleY = if (f) 1.04f else 1f
        }
    }

    private fun text(s: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildLayout() {
        val root = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#050C15")); isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Siberia Connect TV", 32f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        head.addView(text("v${BuildConfig.VERSION_NAME}", 15f, "#94A3B8"))
        page.addView(head, lp())
        wolf = ImageView(this).apply {
            setImageResource(R.drawable.sc_tv_wolf)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFF152B3D.toInt(), 0xFF081523.toInt())
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(82).toFloat()
                setStroke(dp(2), 0xFF62DBF7.toInt())
            }
            contentDescription = getString(R.string.sc_tv_wolf_play)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { playWolf() }
            setOnFocusChangeListener { view, focused ->
                (view.background as GradientDrawable).setStroke(dp(if (focused) 4 else 2),
                    if (focused) 0xFFA3F1FF.toInt() else 0xFF62DBF7.toInt())
            }
        }
        val wolfRow = FrameLayout(this).apply {
            addView(wolf, FrameLayout.LayoutParams(dp(156), dp(156), Gravity.CENTER))
            val sound = tvButton("", "#152B3D", "#334155")
            fun updateSound() {
                val enabled = appPrefs.getBoolean("wolf_sound", true)
                sound.setText(if (enabled) R.string.sc_tv_wolf_sound_on else R.string.sc_tv_wolf_sound_off)
            }
            updateSound()
            sound.setOnClickListener {
                val enabled = !appPrefs.getBoolean("wolf_sound", true)
                appPrefs.edit().putBoolean("wolf_sound", enabled).apply()
                if (!enabled) releaseWolfAudio()
                updateSound()
            }
            addView(sound, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        page.addView(wolfRow, lp(16))

        btnUpdate = tvButton("Установить обновление", "#715521", "#805E23").apply {
            visibility = View.GONE; setOnClickListener { downloadUpdate() }
        }
        page.addView(btnUpdate, lp(16))

        // ── вход: QR + мастер-ключ (общий Compose-экран с телефоном) ──
        pairView = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SiberiaArcticTheme {
                    val st by authVm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(st.activatedLink) { st.activatedLink?.let { onPaired(it) } }
                    ScAuthContent(st, authVm::onCodeChange, authVm::submit, authVm::newPairing, authVm::resumePairing, authVm::pausePairing)
                }
            }
        }
        page.addView(pairView, lp(24))

        // ── главный экран ──
        mainView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(28)); background = bg("#101F2B"); visibility = View.GONE
        }
        tvStatus = text("", 28f, bold = true)
        tvInfo = text("", 24f, "#CBD5E1")
        tvMsg = text("", 24f, "#E9C37D")
        btnConnect = tvButton("Подключить", "#005469", "#00677A", primary = true).apply {
            setPadding(dp(24), dp(26), dp(24), dp(26))
            setOnClickListener {
                if (!busy && !running) playWolf()
                toggleVpn()
            }
        }
        btnLoc = tvButton("", "#152B3D", "#334155").apply { setOnClickListener { cycleLocation() } }
        btnBoot = tvButton("", "#152B3D", "#334155").apply {
            setOnClickListener { MmkvManager.encodeStartOnBoot(!MmkvManager.decodeStartOnBoot()); refreshUi() }
        }
        btnLogout = tvButton("Выйти из аккаунта", "#152B3D", "#7F1D1D", 24f).apply { setOnClickListener { signedOut("вы вышли") } }
        mainView.addView(tvStatus); mainView.addView(tvInfo, lp(6)); mainView.addView(tvMsg, lp(6))
        mainView.addView(btnConnect, lp(22)); mainView.addView(btnLoc, lp(14)); mainView.addView(btnBoot, lp(10)); mainView.addView(btnLogout, lp(10))
        page.addView(mainView, lp(24))

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val horizontal = (right - left) / 20
            val vertical = (bottom - top) / 20
            if (page.paddingLeft != horizontal || page.paddingTop != vertical) {
                page.setPadding(horizontal, vertical, horizontal, vertical)
            }
        }
        root.addView(page)
        setContentView(root)
    }

    // ───────────── экраны ─────────────

    private fun route() {
        if (account.token.isEmpty()) showPair() else showMain()
    }

    private fun showMain() = runOnUiThread {
        authVm.pairingEnabled = false
        authVm.pausePairing()
        pairView.visibility = View.GONE
        mainView.visibility = View.VISIBLE
        refreshUi()
        btnConnect.requestFocus()
    }

    private fun say(s: String) = runOnUiThread { if (::tvMsg.isInitialized) tvMsg.text = s }

    private fun refreshUi() = runOnUiThread {
        when {
            busy -> { tvStatus.text = "● Подключение…"; tvStatus.setTextColor(Color.parseColor("#E9C37D")); btnConnect.text = "Подождите…" }
            running -> { tvStatus.text = "● Подключено"; tvStatus.setTextColor(Color.parseColor("#57D6B4")); btnConnect.text = "Отключить" }
            else -> { tvStatus.text = "● Отключено"; tvStatus.setTextColor(Color.parseColor("#ABC0CD")); btnConnect.text = "Подключить" }
        }
        val sel = MmkvManager.getSelectServer()
        val name = if (!sel.isNullOrEmpty()) MmkvManager.decodeServerConfig(sel)?.remarks else null
        val auto = appPrefs.getBoolean("auto", true)
        val irbis = name?.contains("Ирбис", ignoreCase = true) == true
        val title = if (irbis) "Ирбис · Южный" else name
            ?.split(Regex("\\s*[·•|]\\s*"))
            ?.filterNot { it in setOf("Нидерланды", "Финляндия", "Россия") }
            ?.joinToString(" · ")
        btnLoc.text = if (auto) "Сервер: Автовыбор" else "Сервер: ${title ?: "—"}"
        val flag = if (irbis && !auto) ContextCompat.getDrawable(this, R.drawable.sc_tv_flag_kz)?.apply {
            setBounds(0, 0, dp(32), dp(24))
        } else null
        btnLoc.setCompoundDrawablesRelative(flag, null, null, null)
        btnLoc.compoundDrawablePadding = dp(8)
        tvInfo.text = if (!running && !ScVpn.hasSub()) "Ключ ещё не получен — нажмите «Подключить»" else
            (title?.let { if (irbis) "$it\nКазахстан · СНГ и сервисы" else "Сервер: $it" } ?: "")
        btnBoot.text = if (MmkvManager.decodeStartOnBoot()) "Автозапуск включён" else "Автозапуск выключен"
        btnConnect.isEnabled = !busy
    }

    // ───────────── вход (QR + мастер-ключ) ─────────────

    private fun showPair() = runOnUiThread {
        mainView.visibility = View.GONE
        pairView.visibility = View.VISIBLE
        authVm.pairingEnabled = true
        authVm.resumePairing()
        pairView.requestFocus()
    }

    /** Вход выполнен (QR подтверждён на телефоне или введён мастер-ключ): токен уже сохранён ScAccount. */
    private fun onPaired(link: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (link.isNotEmpty()) runCatching { ScVpn.applySubAny(this@ScTvActivity, did, link) }
            else runCatching { account.status()?.let { ScVpn.applyActiveLink(this@ScTvActivity, did, it) } }
            showMain()
            val st = account.last
            if (st != null && st.optString("state") in ScAccount.BAD_STATES) say(st.optString("message").ifEmpty { "Нет активной подписки" })
        }
    }

    // ───────────── подключение ─────────────

    private fun firstRunDefaults() {
        if (appPrefs.getBoolean("tv_defaults_v1", false)) return
        lifecycleScope.launch(Dispatchers.IO) {
            // российские сайты и адреса напрямую — как в мобильном приложении
            runCatching { SettingsManager.resetRoutingRulesets(ScActivity.RU_ROUTING) }
            MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, false)    // без лишней статистики в уведомлении на ТВ
            appPrefs.edit().putBoolean("tv_defaults_v1", true).putBoolean("auto", true).apply()
        }
    }

    private fun cycleLocation() {
        val sub = ScVpn.ourSub() ?: run { say("Сначала подключитесь — список локаций придёт с ключом"); return }
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.isEmpty()) return
        val auto = appPrefs.getBoolean("auto", true)
        val sel = MmkvManager.getSelectServer()
        // порядок: Авто → сервер 1 → сервер 2 → … → Авто
        if (auto) {
            appPrefs.edit().putBoolean("auto", false).apply()
            MmkvManager.setSelectServer(list.first())
        } else {
            val i = list.indexOf(sel)
            if (i < 0 || i >= list.size - 1) appPrefs.edit().putBoolean("auto", true).apply()
            else MmkvManager.setSelectServer(list[i + 1])
        }
        if (running) LauncherManager.restartService(this)
        refreshUi()
    }

    private fun toggleVpn() {
        if (busy) return
        if (running) { LauncherManager.stopService(this); return }
        busy = true
        say("")
        refreshUi()
        lifecycleScope.launch(Dispatchers.IO) {
            val st = runCatching { account.status() }.getOrNull()
            when (account.outcome) {
                ScAccount.Outcome.REVOKED -> { busy = false; signedOut("сессия закончилась"); return@launch }
                ScAccount.Outcome.LOCKED, ScAccount.Outcome.REBIND -> {
                    busy = false; say("Ключ привязан к другому устройству. Привяжите телевизор заново"); refreshUi(); return@launch
                }
                else -> Unit
            }
            if (st != null) {
                val state = st.optString("state")
                if (state in ScAccount.BAD_STATES) {
                    busy = false; say(st.optString("message").ifEmpty { "Нет активной подписки" }); refreshUi(); return@launch
                }
                runCatching { ScVpn.applyActiveLink(this@ScTvActivity, did, st) }
            }
            if (!ScVpn.hasSub()) {
                busy = false; say("Не удалось получить ключ. Проверьте интернет и попробуйте ещё раз"); refreshUi(); return@launch
            }
            if (appPrefs.getBoolean("auto", true)) runCatching { ScVpn.pickAuto(this@ScTvActivity, account, 2) }
            runOnUiThread { requestStart() }
        }
    }

    private fun requestStart() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) { busy = false; say("Нет сервера. Обновите ключ"); refreshUi(); return }
        if (!SettingsManager.isVpnMode()) { startCore(); return }
        val intent = VpnService.prepare(this)
        if (intent == null) startCore() else vpnPermission.launch(intent)
    }

    private fun startCore() {
        lifecycleScope.launch(Dispatchers.IO) {
            ensureGeoFiles()
            runOnUiThread { LauncherManager.startService(this@ScTvActivity) }
        }
    }

    private fun ensureGeoFiles() {
        runCatching {
            val dir = File(Utils.userAssetPath(this))
            dir.mkdirs()
            assets.list("")?.filter { it.startsWith("geo") && it.endsWith(".dat") }?.forEach { n ->
                val f = File(dir, n)
                if (!f.exists() || f.length() == 0L) assets.open(n).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    // ───────────── выход / отзыв ключа ─────────────

    /** Тихая проверка при возврате на экран: ключ могли отозвать или привязать телевизор к другому аккаунту. */
    private suspend fun recheckAccount() {
        val st = account.status()
        if (account.outcome == ScAccount.Outcome.REVOKED) { signedOut("сессия закончилась"); return }
        if (st != null && st.optString("state") == "active") ScVpn.applyActiveLink(this, did, st)
    }

    private fun signedOut(reason: String) {
        account.signOut()
        ScVpn.dropSub(this)
        runOnUiThread {
            runCatching { LauncherManager.stopService(this) }
            running = false; busy = false
            authVm.resetSession()
            showPair()
            toastSignedOut(reason)
        }
    }

    private fun toastSignedOut(reason: String) {
        android.widget.Toast.makeText(this, "Вы вышли из аккаунта ($reason). Привяжите телевизор заново", android.widget.Toast.LENGTH_LONG).show()
    }

    // ───────────── обновление (отдельный манифест TV: version-tv.json) ─────────────

    private fun semver(a: String, b: String): Int {
        fun p(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = p(a); val y = p(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-cache")
        try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
    }.getOrNull()

    private fun checkUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(5000)
            val j = getJson("https://${ScActivity.PRIMARY_HOST}/app/dl/version-tv.json?t=${System.currentTimeMillis()}") ?: return@launch
            val v = j.optString("version")
            if (v.isEmpty() || semver(v, BuildConfig.VERSION_NAME) <= 0) return@launch   // сравнение по версии: новее — предлагаем, иначе молчим
            updSha = j.optString("sha256").lowercase()
            runOnUiThread { btnUpdate.text = "ДОСТУПНО ОБНОВЛЕНИЕ $v — НАЖМИТЕ ОК"; btnUpdate.visibility = View.VISIBLE }
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun downloadUpdate() {
        updFile?.let { if (it.exists()) { install(it); return } }
        runOnUiThread { btnUpdate.text = "СКАЧИВАЕМ ОБНОВЛЕНИЕ…"; btnUpdate.isEnabled = false }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(cacheDir, "upd").apply { mkdirs() }
                val tmp = File(dir, "dl.part")
                val c = URL("https://${ScActivity.PRIMARY_HOST}/app/dl/SiberiaConnectTV.apk").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200) error("сервер ответил ${c.responseCode}")
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n) }
                } }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (updSha.isNotEmpty() && sha != updSha) { tmp.delete(); error("файл повреждён") }
                if (packageManager.getPackageArchiveInfo(tmp.path, 0)?.packageName != packageName) { tmp.delete(); error("чужой пакет") }
                val f = File(dir, "SiberiaConnectTV.apk")
                f.delete()
                if (!tmp.renameTo(f)) error("не удалось сохранить файл")
                updFile = f
                runOnUiThread { btnUpdate.isEnabled = true; install(f) }
            } catch (e: Exception) {
                runOnUiThread { btnUpdate.isEnabled = true; btnUpdate.text = "НЕ УДАЛОСЬ СКАЧАТЬ — НАЖМИТЕ ОК, ЧТОБЫ ПОВТОРИТЬ" }
            }
        }
    }

    private fun install(f: File) {
        if (!canInstall()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.scupdate", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { btnUpdate.text = "НЕ УДАЛОСЬ ОТКРЫТЬ УСТАНОВЩИК" }
    }
}
