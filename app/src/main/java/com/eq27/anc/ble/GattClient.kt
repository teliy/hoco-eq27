package com.eq27.anc.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.eq27.anc.protocol.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * GATT-клиент для наушников.
 *
 * Ключевые решения:
 *  - все GATT-операции сериализуются через [opMutex]: Android BLE не переносит
 *    параллельные write/descriptor-операции (молча теряются);
 *  - каждая операция завершается колбэком в Continuation c таймаутом;
 *  - при ошибке подключения (классический status 133 у дешёвых чипов Jieli)
 *    делаем до [MAX_ATTEMPTS] попыток;
 *  - таблица сервисов разбирается автоматически: приоритет отдан сервисам Jieli
 *    (UUID вида 0000ae00-… / 0000ae80-…), в них ищем TX (write) и RX (notify).
 */
class GattClient(
    private val context: Context,
    private val log: (level: String, message: String) -> Unit,
) {

    val state = MutableStateFlow<ConnState>(ConnState.Idle)
    val gattTable = MutableStateFlow<GattTable?>(null)
    val notifications = MutableSharedFlow<RxPacket>(extraBufferCapacity = 128)
    val writeCharUuid = MutableStateFlow<String?>(null)
    val notifyCharUuid = MutableStateFlow<String?>(null)

    /** Автопереподключение при обрыве (управляется из UI через [MainViewModel]). */
    @Volatile
    var autoReconnect: Boolean = true

    /** Желаемый размер куска при отправке длинных кадров (0 = автоматически от MTU). */
    @Volatile
    var chunkSizePref: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val opMutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private val connectAttempts = AtomicInteger(0)
    @Volatile private var userDisconnect = false

    // Ожидающие продолжения текущей операции (операции строго по одной)
    private var pendingDiscover: CompletableDeferred<Boolean>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescriptor: CompletableDeferred<Boolean>? = null

    // UUID строки -> характеристики (для быстрого доступа при записи)
    private val charCache = HashMap<String, BluetoothGattCharacteristic>()

    // ------------------------------------------------------------------ подключение

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val current = state.value
        if (current is ConnState.Connected || current is ConnState.Connecting) {
            log("E", "Уже есть активное соединение — сначала отключитесь")
            return
        }
        lastDevice = device
        connectAttempts.set(1)
        userDisconnect = false
        state.value = ConnState.Connecting(deviceName(device), device.address, 1)
        doConnect(device)
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(device: BluetoothDevice) {
        runCatching { gatt?.close() }
        val attempt = connectAttempts.get()
        log("I", "Подключение к ${deviceName(device)} (попытка $attempt)…")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (g == null) {
            log("E", "connectGatt() вернул null — перезапустите Bluetooth и повторите")
            state.value = ConnState.Failed("connectGatt() вернул null. Перезапустите Bluetooth.")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnect = true
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        charCache.clear()
        gattTable.value = null
        writeCharUuid.value = null
        notifyCharUuid.value = null
        state.value = ConnState.Idle
        log("I", "Отключено по команде пользователя")
    }

    /** Полное освобождение ресурсов (вызывается при закрытии приложения). */
    fun shutdown() {
        userDisconnect = true
        runCatching { gatt?.close() }
        gatt = null
        scope.cancel()
    }

    // ------------------------------------------------------------------ отправка

    /**
     * Отправка кадра (или любого набора байт) в выбранную TX-характеристику.
     * Длинные данные автоматически режутся на куски по MTU-3 (или по [chunkSizePref]).
     */
    suspend fun write(data: ByteArray, noResponse: Boolean): Boolean {
        val g = gatt ?: run { log("E", "Нет подключения к наушникам"); return false }
        val uuidStr = writeCharUuid.value
            ?: run { log("E", "TX-характеристика не выбрана — откройте «Лабораторию → Дамп GATT» и выберите вручную"); return false }
        val c = charCache[uuidStr]
            ?: run { log("E", "Характеристика $uuidStr исчезла из кэша — переподключитесь"); return false }

        val canNoResp =
            (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val useNoResp = noResponse && canNoResp
        if (noResponse && !canNoResp) {
            log("W", "Характеристика не поддерживает WRITE_NO_RESPONSE — пишу с подтверждением")
        }

        val chunk = if (chunkSizePref > 0) chunkSizePref else chunkAuto()
        if (chunk < data.size) {
            log("I", "Кадр ${data.size} Б больше куска $chunk Б — шлю частями")
        }

        return opMutex.withLock {
            var offset = 0
            var first = true
            while (offset < data.size) {
                val end = min(offset + chunk, data.size)
                val part = data.copyOfRange(offset, end)
                val ok = writeOnceLocked(g, c, part, useNoResp, first)
                if (!ok) return false
                offset = end
                first = false
                if (offset < data.size) delay(15)
            }
            true
        }
    }

    private fun chunkAuto(): Int {
        val mtu = (state.value as? ConnState.Connected)?.mtu ?: 23
        return (mtu - 3).coerceIn(20, 244)
    }

    private suspend fun writeOnceLocked(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        part: ByteArray,
        noResp: Boolean,
        first: Boolean,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val initiated = if (Build.VERSION.SDK_INT >= 33) {
            val type =
                if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // В API 33+ возвращается int-статус: 0 == GATT_SUCCESS
            runCatching { g.writeCharacteristic(c, part, type) }
                .getOrDefault(Int.MIN_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                c.writeType =
                    if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = part
                g.writeCharacteristic(c)
            }.getOrDefault(false)
        }
        if (!initiated) {
            pendingWrite = null
            log("E", "writeCharacteristic() отклонён системой")
            return false
        }
        if (first) log("T", "TX ${part.size} Б → ${part.hex()}")
        val result = withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() }
        pendingWrite = null
        return if (result == true) {
            true
        } else {
            log(
                "E",
                if (result == null) "Запись не подтверждена (таймаут ${OP_TIMEOUT_MS} мс)"
                else "Запись отклонена наушниками (status=GATT_ERROR)",
            )
            false
        }
    }

    // ------------------------------------------------------------------ RX / выбор характеристик

    fun setWriteTarget(uuidStr: String?) {
        writeCharUuid.value = uuidStr
        if (uuidStr != null) log("I", "TX-характеристика: ${shortUuid(uuidStr)}")
    }

    fun setNotifyTarget(uuidStr: String?) {
        notifyCharUuid.value = uuidStr
        if (uuidStr != null && state.value is ConnState.Connected) {
            scope.launch { enableNotificationsLocked(uuidStr) }
        }
    }

    private suspend fun enableNotificationsLocked(uuidStr: String): Boolean = opMutex.withLock {
        val g = gatt ?: return@withLock false
        val c = charCache[uuidStr] ?: run {
            log("E", "Характеристика ${shortUuid(uuidStr)} не найдена")
            return@withLock false
        }
        val cccd = c.descriptors.firstOrNull { it.uuid == UUID_CCCD }
        if (cccd == null) {
            log("W", "У ${shortUuid(uuidStr)} нет дескриптора CCCD — уведомления не включаются")
            return@withLock false
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingDescriptor = deferred
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val initiated = if (Build.VERSION.SDK_INT >= 33) {
            // В API 33+ возвращается int-статус: 0 == GATT_SUCCESS
            runCatching { g.writeDescriptor(cccd, value) }
                .getOrDefault(Int.MIN_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                cccd.value = value
                g.writeDescriptor(cccd)
            }.getOrDefault(false)
        }
        if (!initiated) {
            pendingDescriptor = null
            log("E", "Запись CCCD отклонена системой")
            return@withLock false
        }
        val ok = withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: false
        pendingDescriptor = null
        if (ok) log("I", "Уведомления включены: ${shortUuid(uuidStr)}")
        else log("E", "CCCD не записана — входящие уведомления могут не приходить")
        ok
    }

    // ------------------------------------------------------------------ колбэки GATT

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("W", "Канал открыт, но status=$status")
                    }
                    log("I", "GATT-канал открыт, разбираем сервисы…")
                    val started = runCatching { g.discoverServices() }.getOrDefault(false)
                    if (!started) {
                        log("E", "discoverServices() не запустился")
                        failOrRetry(g)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (userDisconnect) return
                    if (state.value is ConnState.Connecting) {
                        log("W", "Обрыв во время подключения (status=$status)")
                        failOrRetry(g)
                    } else if (state.value is ConnState.Connected) {
                        onDisconnectedByDevice(g)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingDiscover?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingDiscover = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("E", "Сервисы не обнаружены, status=$status")
                failOrRetry(g)
                return
            }
            scope.launch {
                val mtu = requestMtuLocked(247)
                val table = buildTable(g)
                gattTable.value = table
                writeCharUuid.value = table.suggestedWrite
                notifyCharUuid.value = table.suggestedNotify

                log(
                    "I",
                    "TX=${table.suggestedWrite?.let { shortUuid(it) } ?: "—"}  " +
                            "RX=${table.suggestedNotify?.let { shortUuid(it) } ?: "—"}",
                )
                val notify = table.suggestedNotify
                if (notify != null) enableNotificationsLocked(notify)

                state.value = ConnState.Connected(deviceName(g.device), g.device.address, mtu)
                log("I", "Готово: MTU=$mtu. Наушники подключены, можно слать кадры.")
                log("I", "Совет: «Лаборатория → Дамп GATT» покажет все сервисы и характеристики.")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingMtu?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
            pendingMtu = null
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onCharacteristicChanged(g, characteristic, value)"))
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ (API 33): новый колбэк с value
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitRx(characteristic.uuid.toString(), value)
        }

        // Android 12 и ниже (API 31/32): старый колбэк, значение в characteristic.value
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            emitRx(characteristic.uuid.toString(), value)
        }
    }

    private fun emitRx(uuidStr: String, value: ByteArray) {
        notifications.tryEmit(RxPacket(uuidStr, value.copyOf(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ служебное

    private suspend fun requestMtuLocked(mtu: Int): Int = opMutex.withLock {
        val g = gatt ?: return@withLock 23
        val deferred = CompletableDeferred<Int>()
        pendingMtu = deferred
        val ok = runCatching { g.requestMtu(mtu) }.getOrDefault(false)
        if (!ok) {
            pendingMtu = null
            log("W", "requestMtu() отклонён — работаем с MTU=23")
            return@withLock 23
        }
        val result = withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: 23
        pendingMtu = null
        if (result < 24) log("W", "MTU=$result — длинные кадры уйдут частями")
        result
    }

    private fun buildTable(g: BluetoothGatt): GattTable {
        charCache.clear()
        val serviceInfos = mutableListOf<GattServiceInfo>()
        for (svc in g.services) {
            val chars = mutableListOf<GattCharInfo>()
            for (c in svc.characteristics) {
                charCache[c.uuid.toString()] = c
                chars.add(GattCharInfo(c.uuid.toString(), c.properties, c.instanceId))
            }
            serviceInfos.add(GattServiceInfo(svc.uuid.toString(), chars))
        }
        // Приоритет: сначала сервисы Jieli (0000ae…), затем прочие нестандартные,
        // стандартные (1800/1801/180a и т.п.) — в конце.
        val priority = serviceInfos.sortedWith(
            compareBy(
                { !it.uuid.startsWith("0000ae") },
                { it.uuid.startsWith("000018") || it.uuid.startsWith("0000fe") },
            ),
        )
        var write: String? = null
        var notify: String? = null
        outer@ for (svc in priority) {
            for (c in svc.characteristics) {
                if (write == null &&
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ) write = c.uuid
                if (write == null &&
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ) write = c.uuid
                if (notify == null &&
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ) notify = c.uuid
                if (write != null && notify != null) break@outer
            }
        }
        return GattTable(serviceInfos, write, notify)
    }

    private fun failOrRetry(g: BluetoothGatt) {
        val attempt = connectAttempts.get()
        if (!userDisconnect && autoReconnect && attempt < MAX_ATTEMPTS) {
            connectAttempts.incrementAndGet()
            val dev = g.device
            log("W", "Попытка $attempt не удалась — повтор через 400 мс")
            runCatching { g.close() }
            scope.launch {
                delay(400)
                if (state.value is ConnState.Connecting) doConnect(dev)
            }
        } else {
            runCatching { g.close() }
            gatt = null
            state.value = ConnState.Failed(
                "Подключение не удалось после $attempt попыток. " +
                        "Подсказки: уберите наушники из кейса, закройте hoco.music, " +
                        "включите/выключите Bluetooth. Подробности — в README (Траблшутинг).",
            )
        }
    }

    private fun onDisconnectedByDevice(g: BluetoothGatt) {
        runCatching { g.close() }
        gatt = null
        charCache.clear()
        gattTable.value = null
        val dev = lastDevice
        if (autoReconnect && dev != null) {
            log("I", "Наушники отключились — автопереподключение…")
            connectAttempts.set(1)
            state.value = ConnState.Connecting(deviceName(dev), dev.address, 1)
            scope.launch {
                delay(500)
                if (state.value is ConnState.Connecting) doConnect(dev)
            }
        } else {
            writeCharUuid.value = null
            notifyCharUuid.value = null
            state.value = ConnState.Idle
            log("I", "Соединение потеряно")
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull()?.takeUnless { it.isBlank() } ?: d.address

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val OP_TIMEOUT_MS = 3500L

        /** Дескриптор Client Characteristic Configuration. */
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Короткое красивое представление UUID: 0000ae01-… → «AE01». */
        fun shortUuid(u: String): String =
            u.removePrefix("0000").take(4).uppercase()
    }
}
