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
