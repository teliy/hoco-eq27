package com.eq27.anc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eq27.anc.ble.ConnState
import com.eq27.anc.ble.GattClient
import com.eq27.anc.ble.GattTable
import com.eq27.anc.ble.LogEntry
import com.eq27.anc.ble.RxPacket
import com.eq27.anc.ble.ScanEvent
import com.eq27.anc.ble.ScanItem
import com.eq27.anc.ble.ScannerSource
import com.eq27.anc.protocol.CommandStore
import com.eq27.anc.protocol.CustomCommand
import com.eq27.anc.protocol.CrcVariant
import com.eq27.anc.protocol.RcspCodec
import com.eq27.anc.protocol.hex
import com.eq27.anc.protocol.parseHexLoose
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Режимы шумоподавления, доступные на вкладке «Шумодав». */
enum class AncMode(val label: String, val short: String) {
    OFF("Выкл", "ANC выкл"),
    ON("ANC", "Шумоподавление"),
    TRANSPARENCY("Прозрачный", "Пропуск голосов"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ------------------------------------------------------------------ слои

    val scanner = ScannerSource(app)
    private val client = GattClient(app) { level, msg -> addLog(level, msg) }
    val store = CommandStore(app)

    val conn: StateFlow<ConnState> = client.state
    val table: StateFlow<GattTable?> = client.gattTable
    val notifications: SharedFlow<RxPacket> = client.notifications
    val writeChar: StateFlow<String?> = client.writeCharUuid
    val notifyChar: StateFlow<String?> = client.notifyCharUuid

    // ------------------------------------------------------------------ журналирование

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    /** Копилка последних входящих пакетов (для ленты на вкладке «Шумодав»). */
    private val _recentRx = MutableStateFlow<List<RxPacket>>(emptyList())
    val recentRx: StateFlow<List<RxPacket>> = _recentRx

    init {
        viewModelScope.launch {
            client.notifications.collect { rx ->
                _recentRx.value = (_recentRx.value + rx).takeLast(20)
                addLog("R", "RX ${GattClient.shortUuid(rx.uuid)} (${rx.bytes.size} Б): ${rx.bytes.hex()}")
            }
        }
    }

    fun addLog(level: String, message: String) {
        val entry = LogEntry(timeFmt.format(Date()), level, message)
        _log.value = (_log.value + entry).takeLast(600)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ------------------------------------------------------------------ сканирование

    private val _scanItems = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanItems: StateFlow<List<ScanItem>> = _scanItems

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    private var scanJob: Job? = null

    fun bluetoothReady(): Boolean = scanner.bluetoothReady()

    fun startScan(nameFilter: String, showAll: Boolean) {
        if (_scanning.value) return
        _scanItems.value = emptyList()
        _scanError.value = null
        _scanning.value = true
        val filterNote = if (!showAll && nameFilter.isNotBlank()) " (фильтр имени: «$nameFilter»)" else ""
        addLog("I", "Сканирование запущено$filterNote")
        scanJob = viewModelScope.launch {
            scanner.scan().collect { event ->
                when (event) {
                    is ScanEvent.Found -> {
                        val item = event.item
                        val matches = showAll || nameFilter.isBlank() ||
                                item.name.contains(nameFilter.trim(), ignoreCase = true)
                        if (!matches) return@collect
                        val current = _scanItems.value.toMutableList()
                        val idx = current.indexOfFirst { it.address == item.address }
                        if (idx >= 0) current[idx] = item else current.add(item)
                        _scanItems.value = current.sortedByDescending { it.rssi }
                    }

                    is ScanEvent.Error -> {
                        _scanError.value = event.message
                        addLog("E", event.message)
                        stopScanInternal()
                    }
                }
            }
        }
    }

    fun stopScan() {
        if (_scanning.value) addLog("I", "Сканирование остановлено")
        stopScanInternal()
    }

    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    // ------------------------------------------------------------------ подключение

    private var lastDevice: android.bluetooth.BluetoothDevice? = null

    fun connect(item: ScanItem) {
        stopScanInternal()
        lastDevice = item.device
        client.autoReconnect = autoReconnect.value
        client.chunkSizePref = chunkSize.value
        client.connect(item.device)
    }

    fun reconnect() {
        val d = lastDevice
        if (d == null) addLog("W", "Нет устройства для повторного подключения")
        else client.connect(d)
    }

    fun disconnect() = client.disconnect()

    // ------------------------------------------------------------------ подключение по адресу

    /**
     * Приводит введённый адрес к виду XX:XX:XX:XX:XX:XX.
     * Понимает форматы с ":", "-" и без разделителей.
     * @return нормализованный адрес или null, если формат неверный
     */
    fun normalizeMac(raw: String): String? {
        val cleaned = raw.trim()
            .replace("-", ":")
            .replace(" ", "")
            .replace(":", "")
        if (cleaned.length != 12 || cleaned.any { it !in "0123456789ABCDEFabcdef" }) return null
        return cleaned.uppercase().chunked(2).joinToString(":")
    }

    /**
     * Прямое подключение по MAC-адресу, без сканирования. Выручает, когда
     * наушники молчат в advertising: заняты hoco.music, уже подключены по
     * классике или спят. Адрес берётся из настроек Bluetooth телефона.
     * @return null — подключение запущено; строка — текст ошибки для UI
     */
    fun connectByAddress(addressRaw: String): String? {
        val normalized = normalizeMac(addressRaw)
            ?: return "Неверный формат адреса. Пример: 72:08:03:03:5A:6D"
        val device = scanner.remoteDevice(normalized)
            ?: return "Bluetooth выключен или недоступен"
        stopScanInternal()
        addLog("I", "Прямое подключение по адресу $normalized (без сканирования)")
        lastDevice = device
        client.autoReconnect = autoReconnect.value
        client.chunkSizePref = chunkSize.value
        client.connect(device)
        return null
    }

    // ------------------------------------------------------------------ настройки

    val autoReconnect = MutableStateFlow(store.autoReconnect)
    val crcVariant = MutableStateFlow(indexToVariant(store.crcVariantIndex))
    val crcIncludeLen = MutableStateFlow(store.crcIncludeLen)
    val chunkSize = MutableStateFlow(store.chunkSize)
    val writeNoResponse = MutableStateFlow(store.writeNoResponse)

    fun setAutoReconnect(on: Boolean) {
        autoReconnect.value = on
        store.autoReconnect = on
        client.autoReconnect = on
    }

    fun setCrcVariant(v: CrcVariant) {
        crcVariant.value = v
        store.crcVariantIndex = v.ordinal
    }

    fun setCrcIncludeLen(b: Boolean) {
        crcIncludeLen.value = b
        store.crcIncludeLen = b
    }

    fun setChunkSize(n: Int) {
        val v = n.coerceIn(0, 244)
        chunkSize.value = v
        store.chunkSize = v
        client.chunkSizePref = v
    }

    fun setWriteNoResponse(b: Boolean) {
        writeNoResponse.value = b
        store.writeNoResponse = b
    }

    // ------------------------------------------------------------------ кадры ANC

    private val _ancOn = MutableStateFlow(store.ancOnHex)
    val ancOn: StateFlow<String> = _ancOn

    private val _ancOff = MutableStateFlow(store.ancOffHex)
    val ancOff: StateFlow<String> = _ancOff

    private val _transparency = MutableStateFlow(store.transparencyHex)
    val transparency: StateFlow<String> = _transparency

    fun setAncPreset(mode: AncMode, hex: String) {
        when (mode) {
            AncMode.OFF -> {
                store.ancOffHex = hex
                _ancOff.value = hex
            }

            AncMode.ON -> {
                store.ancOnHex = hex
                _ancOn.value = hex
            }

            AncMode.TRANSPARENCY -> {
                store.transparencyHex = hex
                _transparency.value = hex
            }
        }
        addLog("I", "Кадр «${mode.label}» сохранён")
    }

    fun applyAnc(mode: AncMode) {
        val hexStr = when (mode) {
            AncMode.OFF -> _ancOff.value
            AncMode.ON -> _ancOn.value
            AncMode.TRANSPARENCY -> _transparency.value
        }
        if (hexStr.isBlank()) {
            addLog(
                "W",
                "Кадр для «${mode.label}» ещё не задан. Перехватите его из hoco.music (README, раздел «Поймать команду ANC») " +
                        "и сохраните кнопкой «Изменить кадры».",
            )
            return
        }
        sendHex(hexStr)
    }

    // ------------------------------------------------------------------ отправка

    fun sendHex(hexStr: String) {
        val bytes = hexStr.parseHexLoose()
        if (bytes == null || bytes.isEmpty()) {
            addLog("E", "HEX не разобран — проверьте запись (пример: FE DC BA 04 00 74 01 00)")
            return
        }
        client.chunkSizePref = chunkSize.value
        viewModelScope.launch {
            client.write(bytes, writeNoResponse.value)
        }
    }

    fun setWriteTarget(uuid: String?) = client.setWriteTarget(uuid)

    fun setNotifyTarget(uuid: String?) = client.setNotifyTarget(uuid)

    // ------------------------------------------------------------------ конструктор / решатель

    /**
     * Собирает RCSP-кадр из команды и payload.
     * @return HEX кадра или null при ошибке ввода
     */
    fun buildFrame(cmdText: String, payloadText: String): String? {
        val normalized = cmdText.trim().removePrefix("0x").removePrefix("0X")
        val cmd = normalized.toIntOrNull(16) ?: return null
        if (cmd !in 0..0xFFFF) return null
        val payload = payloadText.parseHexLoose() ?: ByteArray(0)
        val frame = RcspCodec.buildFrame(cmd, payload, crcVariant.value, crcIncludeLen.value)
        return frame.hex()
    }

    fun solveCrc(hexStr: String): String = RcspCodec.solveCrc(hexStr)

    // ------------------------------------------------------------------ мои команды

    private val _customCommands = MutableStateFlow(store.customCommands())
    val customCommands: StateFlow<List<CustomCommand>> = _customCommands

    fun addCustom(name: String, hex: String) {
        val list = _customCommands.value + CustomCommand(name.ifBlank { "Команда" }, hex)
        store.saveCustomCommands(list)
        _customCommands.value = list
    }

    fun removeCustom(index: Int) {
        if (index !in _customCommands.value.indices) return
        val list = _customCommands.value.toMutableList().apply { removeAt(index) }
        store.saveCustomCommands(list)
        _customCommands.value = list
    }

    // ------------------------------------------------------------------ прочее

    override fun onCleared() {
        client.shutdown()
        super.onCleared()
    }

    private fun indexToVariant(index: Int): CrcVariant =
        CrcVariant.entries.getOrElse(index) { CrcVariant.CCITT_FALSE }
}
