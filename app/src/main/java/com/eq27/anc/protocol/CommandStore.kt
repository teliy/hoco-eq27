package com.eq27.anc.protocol

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Пользовательская команда из «Лаборатории». */
data class CustomCommand(
    val name: String,
    val hex: String,
)

/**
 * Простое хранилище настроек и кадров на SharedPreferences.
 * Сознательно без DataStore/Room: проект-стартер должен собираться и читаться легко.
 */
class CommandStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Кадры ANC (заполняются после перехвата из hoco.music) ---

    var ancOnHex: String
        get() = prefs.getString(KEY_ANC_ON, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ANC_ON, v).apply()

    var ancOffHex: String
        get() = prefs.getString(KEY_ANC_OFF, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ANC_OFF, v).apply()

    var transparencyHex: String
        get() = prefs.getString(KEY_TRANSPARENCY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TRANSPARENCY, v).apply()

    // --- Параметры протокола ---

    var crcVariantIndex: Int
        get() = prefs.getInt(KEY_CRC_VARIANT, CrcVariant.CCITT_FALSE.ordinal)
        set(v) = prefs.edit().putInt(KEY_CRC_VARIANT, v).apply()

    var crcIncludeLen: Boolean
        get() = prefs.getBoolean(KEY_CRC_INCLUDE_LEN, true)
        set(v) = prefs.edit().putBoolean(KEY_CRC_INCLUDE_LEN, v).apply()

    /** Размер куска при отправке (0 = автоматически от MTU). */
    var chunkSize: Int
        get() = prefs.getInt(KEY_CHUNK_SIZE, 0)
        set(v) = prefs.edit().putInt(KEY_CHUNK_SIZE, v).apply()

    /** Писать без подтверждения (WRITE_NO_RESPONSE) — у Jieli TX обычно так. */
    var writeNoResponse: Boolean
        get() = prefs.getBoolean(KEY_WRITE_NO_RESPONSE, true)
        set(v) = prefs.edit().putBoolean(KEY_WRITE_NO_RESPONSE, v).apply()

    /** Автопереподключение при обрыве. */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply()

    // --- Вкладка «Подключение»: настройки поиска ---

    /**
     * «Показывать все BLE» — живёт в настройках, чтобы тумблер не сбрасывался
     * при переходе между вкладками и после перезапуска приложения.
     */
    var showAllBle: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_BLE, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_ALL_BLE, v).apply()

    /** Последний фильтр имени — тоже сохраняем. */
    var nameFilter: String
        get() = prefs.getString(KEY_NAME_FILTER, DEFAULT_NAME_FILTER) ?: DEFAULT_NAME_FILTER
        set(v) = prefs.edit().putString(KEY_NAME_FILTER, v).apply()

    // --- Мои команды ---

    fun customCommands(): List<CustomCommand> {
        val raw = prefs.getString(KEY_CUSTOM, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: "Команда ${i + 1}"
                val hex = o.optString("hex") ?: return@mapNotNull null
                CustomCommand(name, hex)
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomCommands(list: List<CustomCommand>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = org.json.JSONObject()
            o.put("name", c.name)
            o.put("hex", c.hex)
            arr.put(o)
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "eq27_anc"
        const val DEFAULT_NAME_FILTER = "EQ27"
        const val KEY_ANC_ON = "frame_anc_on"
        const val KEY_ANC_OFF = "frame_anc_off"
        const val KEY_TRANSPARENCY = "frame_transparency"
        const val KEY_CRC_VARIANT = "crc_variant"
        const val KEY_CRC_INCLUDE_LEN = "crc_include_len"
        const val KEY_CHUNK_SIZE = "chunk_size"
        const val KEY_WRITE_NO_RESPONSE = "write_no_response"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_SHOW_ALL_BLE = "show_all_ble"
        const val KEY_NAME_FILTER = "name_filter"
        const val KEY_CUSTOM = "custom_commands"
    }
}
