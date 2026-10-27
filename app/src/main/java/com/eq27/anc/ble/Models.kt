package com.eq27.anc.ble

import android.bluetooth.BluetoothGattCharacteristic

/** Состояние GATT-соединения с наушниками. */
sealed interface ConnState {
    data object Idle : ConnState

    data class Connecting(
        val name: String,
        val address: String,
        val attempt: Int,
    ) : ConnState

    data class Connected(
        val name: String,
        val address: String,
        val mtu: Int,
    ) : ConnState

    data class Failed(val message: String) : ConnState
}

/** Найденное при сканировании устройство. */
data class ScanItem(
    val device: android.bluetooth.BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
)

/** События сканирования. */
sealed interface ScanEvent {
    data class Found(val item: ScanItem) : ScanEvent
    data class Error(val message: String) : ScanEvent
}

/** Характеристика в GATT-таблице. */
data class GattCharInfo(
    val uuid: String,
    val properties: Int,
    val instanceId: Int,
)

/** Сервис в GATT-таблице. */
data class GattServiceInfo(
    val uuid: String,
    val characteristics: List<GattCharInfo>,
)

/** Полная таблица сервисов/характеристик + автоматически выбранные TX и RX. */
data class GattTable(
    val services: List<GattServiceInfo>,
    val suggestedWrite: String?,
    val suggestedNotify: String?,
) {
    val writeCandidates: List<String>
        get() = services.flatMap { it.characteristics }
            .filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            .map { it.uuid }
            .distinct()

    val notifyCandidates: List<String>
        get() = services.flatMap { it.characteristics }
            .filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            .map { it.uuid }
            .distinct()
}

/** Входящий пакет от наушников (уведомление/индикация). */
data class RxPacket(
    val uuid: String,
    val bytes: ByteArray,
    val timeMillis: Long,
)

/** Строка журнала приложения. */
data class LogEntry(
    val time: String,
    val level: String,
    val message: String,
)

/** Человекочитаемое описание свойств характеристики. */
fun propertiesText(properties: Int): String {
    val parts = mutableListOf<String>()
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
    return if (parts.isEmpty()) "—" else parts.joinToString("|")
}
