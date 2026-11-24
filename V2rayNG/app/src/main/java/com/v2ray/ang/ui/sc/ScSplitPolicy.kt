package com.v2ray.ang.ui.sc

/**
 * «Умное разделение трафика» — чистая логика режимов (без Android, покрыта юнит-тестами).
 *
 *  - [SMART]: служебные правила + российские домены и адреса напрямую, всё остальное — через VPN;
 *  - [ALL]: «Весь трафик через VPN» — только служебные правила (локальная сеть, торренты, QUIC).
 *
 * Если списки (geo-файлы) недоступны, умные правила не применяются: трафик идёт через VPN, а не ломает запуск ядра.
 * Исключения приложений, настроенные самим пользователем, режимом не затрагиваются.
 */
object ScSplitPolicy {
    const val SMART = "smart"
    const val ALL = "all"

    /** Сохранённый режим; у прежних пользователей — из старого флага «Российские сайты напрямую» (по умолчанию — умное). */
    fun resolveMode(stored: String?, legacyRuDirect: Boolean?): String = when (stored) {
        SMART, ALL -> stored
        else -> if (legacyRuDirect == false) ALL else SMART
    }

    /** Умные правила нужны, только если выбран режим SMART и списки доступны; иначе — всё через VPN. */
    fun useSmartRules(mode: String, geoReady: Boolean): Boolean = mode == SMART && geoReady

    /** Пользователь сам менял исключения приложений (сохранил список или снял приложения с автосписка). */
    fun appsCustomized(savedByUser: Boolean, removedCount: Int): Boolean = savedByUser || removedCount > 0

    enum class AppExceptions { KEEP, DISABLE, ENABLE_AUTO }

    /**
     * Что делать с исключениями приложений при смене режима:
     *  - пользовательские — не трогаем (KEEP);
     *  - автосписок (приложения не из «нужен VPN») выключаем в режиме «Весь трафик» и включаем обратно в умном.
     */
    fun appExceptions(mode: String, customized: Boolean): AppExceptions = when {
        customized -> AppExceptions.KEEP
        mode == ALL -> AppExceptions.DISABLE
        else -> AppExceptions.ENABLE_AUTO
    }

    /**
     * Какие правила должны лежать в настройках движка: "smart", "base" или "safe".
     * Без списков (geo-файлов) любые правила со ссылками geo: не дают запустить ядро — тогда "safe" (локальная сеть диапазонами, остальное через VPN).
     */
    fun rulesKind(mode: String, geoReady: Boolean): String = when {
        !geoReady -> "safe"
        mode == SMART -> "smart"
        else -> "base"
    }

    /**
     * Нужно ли переписывать правила в настройках движка.
     * [have] — что записано (null — не записывалось: старая установка, её правила не трогаем без необходимости).
     */
    fun mustRewriteRules(have: String?, want: String): Boolean =
        have != want && (have != null || want == "safe")
}
