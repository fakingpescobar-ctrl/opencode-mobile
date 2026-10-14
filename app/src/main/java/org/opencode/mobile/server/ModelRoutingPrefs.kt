package org.opencode.mobile.server

import android.content.Context

/**
 * Выбор пользователя по маршрутизации моделей в общих настройках приложения.
 *
 * null из [read] — маршрутизация НЕ включена: тогда в конфиг не пишется ни блок
 * `agent`, ни `small_model`. Это важно для тех, кто пикер не открывал: их конфиг
 * остаётся ровно таким, каким был (плюс закреплённый `model`), и никакая новая
 * секция не появляется «на всякий случай».
 */
internal object ModelRoutingPrefs {
    private const val PREFS = "chat_overlay"
    private const val ENABLED = "model_routing_enabled"
    private const val HEAVY = "model_routing_heavy"
    private const val LIGHT = "model_routing_light"
    private const val SMALL = "model_routing_small"

    /** Тяжёлые агенты по умолчанию — тот же закреплённый дефолт. */
    const val DEFAULT_HEAVY = DEFAULT_FREE_MODEL

    /** Лёгкие агенты по умолчанию — быстрая бесплатная flash-модель. */
    const val DEFAULT_LIGHT = "opencode/mimo-v2.6-flash-free"

    /** Служебные генерации (заголовок, сводка) — та же быстрая модель. */
    const val DEFAULT_SMALL = DEFAULT_LIGHT

    /** Значения маршрутизации или null, если пользователь её не включал. */
    fun read(context: Context): ModelRouting? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ENABLED, false)) return null
        return ModelRouting(
            heavy = prefs.getString(HEAVY, null) ?: DEFAULT_HEAVY,
            light = prefs.getString(LIGHT, null) ?: DEFAULT_LIGHT,
            small = prefs.getString(SMALL, null) ?: DEFAULT_SMALL,
        )
    }

    /** Значения ползунков для UI: сохранённые или дефолты, даже если маршрутизация выключена. */
    fun current(context: Context): ModelRouting {
        val prefs = prefs(context)
        return ModelRouting(
            heavy = prefs.getString(HEAVY, null) ?: DEFAULT_HEAVY,
            light = prefs.getString(LIGHT, null) ?: DEFAULT_LIGHT,
            small = prefs.getString(SMALL, null) ?: DEFAULT_SMALL,
        )
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun save(
        context: Context,
        routing: ModelRouting,
    ) {
        prefs(context)
            .edit()
            .putBoolean(ENABLED, true)
            .putString(HEAVY, routing.heavy)
            .putString(LIGHT, routing.light)
            .putString(SMALL, routing.small)
            .apply()
    }

    /**
     * Выключает управление. Уже записанный блок `agent`/`small_model` в конфиге
     * остаётся: он валиден, а обратная зачистка чужого файла — отдельный риск.
     * Выключение означает лишь «больше не переписывать».
     */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(ENABLED, false).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
