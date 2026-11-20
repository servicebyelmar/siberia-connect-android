package com.v2ray.ang.ui.sc

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.v2ray.ang.BuildConfig
import java.security.MessageDigest

/**
 * Зашифрованное хранилище секретов: токен, секрет установки, VPN-ключ (ссылка подписки), состояние блокировки.
 * Связка устройства: device_uuid считается от ANDROID_ID (формула прежняя — привязка на сервере не ломается),
 * плюс отпечаток ANDROID_ID|MODEL; если файл оказался на другом устройстве (бэкап/перенос) — секреты сбрасываются.
 *
 * Если Keystore недоступен, остаёмся на старом файле sc_app: лучше хранить как раньше, чем потерять ключ.
 */
object ScSecureStore {
    private const val FILE = "sc_secure"
    private const val LEGACY = "sc_app"

    const val K_KEY = "vpn_key"
    const val K_UUID = "device_uuid"
    const val K_FP = "device_fp"
    const val K_LOCK_UNTIL = "lock_until_ms"
    const val K_LOCK_NAME = "lock_device_name"
    const val K_REBIND_NAME = "rebind_device_name"
    private const val K_MIGRATED = "migrated_v1"

    /** Всё, что переезжает из открытого файла в зашифрованный. */
    private val MOVED = listOf(
        "inst_secret", "auth_token", "token_from_install", "auto_reg_done", "acc_guest", "tok_seen",
    )
    private val SECRETS = MOVED + K_KEY + K_LOCK_UNTIL + K_LOCK_NAME + K_REBIND_NAME

    @Volatile
    private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences =
        cached ?: synchronized(this) { cached ?: open(ctx.applicationContext).also { cached = it } }

    private fun open(ctx: Context): SharedPreferences {
        val legacy = ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
        val sec = create(ctx) ?: create(ctx) ?: return legacy
        migrate(legacy, sec)
        bindDevice(ctx, sec)
        if (hw.isEmpty()) hw = computeHw()
        return sec
    }

    private fun create(ctx: Context): SharedPreferences? = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, FILE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    private fun migrate(legacy: SharedPreferences, sec: SharedPreferences) {
        if (sec.getBoolean(K_MIGRATED, false)) return
        val e = sec.edit()
        val l = legacy.edit()
        for (k in MOVED) {
            when (val v = legacy.all[k]) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                else -> continue
            }
            l.remove(k)
        }
        e.putBoolean(K_MIGRATED, true)
        e.commit()          // сначала записали в зашифрованный файл, и только потом чистим старый
        l.apply()
    }

    private fun androidId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "none"

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun bindDevice(ctx: Context, sec: SharedPreferences) {
        val fp = sha("${androidId(ctx)}|${Build.MODEL}").joinToString("") { "%02x".format(it) }
        val old = sec.getString(K_FP, null)
        val e = sec.edit()
        if (old != null && old != fp) SECRETS.forEach { e.remove(it) }   // файл с другого устройства
        val uuid = "and-" + sha("sc:${androidId(ctx)}:${BuildConfig.APPLICATION_ID}").take(12).joinToString("") { "%02x".format(it) }
        e.putString(K_FP, fp).putString(K_UUID, uuid).apply()
    }

    /**
     * trial_guard_v1: аппаратный отпечаток для серверного реестра пробных — SHA-256 от Widevine device unique ID (MediaDrm).
     * Не меняется при выходе из аккаунта, очистке данных и переустановке. Пусто, если DRM недоступен — тогда сервер опирается на device_id.
     * Сервер единственный решает, положен ли пробный; приложение только сообщает, что это за устройство.
     */
    @Volatile
    var hw: String = ""
        private set

    private fun computeHw(): String = runCatching {
        val drm = android.media.MediaDrm(java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
        try {
            val id = drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            sha("sc-hw:" + id.joinToString("") { "%02x".format(it) }).joinToString("") { "%02x".format(it) }
        } finally {
            if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    }.getOrDefault("")

    fun deviceUuid(ctx: Context): String = prefs(ctx).getString(K_UUID, null)
        ?: "and-" + sha("sc:${androidId(ctx)}:${BuildConfig.APPLICATION_ID}").take(12).joinToString("") { "%02x".format(it) }

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().take(80)

    fun key(ctx: Context): String = prefs(ctx).getString(K_KEY, "").orEmpty()
    fun saveKey(ctx: Context, url: String) { prefs(ctx).edit().putString(K_KEY, url).apply() }

    /** Только при явном отказе сервера (401/403/DEVICE_LOCKED/Key Expired). */
    fun clearKey(ctx: Context) { prefs(ctx).edit().remove(K_KEY).apply() }

    fun lockUntil(ctx: Context): Long = prefs(ctx).getLong(K_LOCK_UNTIL, 0L)
    fun lockName(ctx: Context): String = prefs(ctx).getString(K_LOCK_NAME, "").orEmpty()
    fun setLock(ctx: Context, untilMs: Long, name: String) {
        prefs(ctx).edit().putLong(K_LOCK_UNTIL, untilMs).putString(K_LOCK_NAME, name).apply()
    }
    fun rebindName(ctx: Context): String? = prefs(ctx).getString(K_REBIND_NAME, null)
    fun clearLock(ctx: Context) { prefs(ctx).edit().remove(K_LOCK_UNTIL).remove(K_LOCK_NAME).apply() }
}
