package com.eq27.anc.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Источник BLE-сканирования. Фильтрацию по имени делаем на стороне приложения:
 * у HOCO EQ27 advertising-имя может отличаться ("EQ27", "HOCO EQ27", локализованные варианты),
 * а жёсткий ScanFilter по точному имени отсеял бы лишнее навсегда.
 */
class ScannerSource(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Готов ли Bluetooth к сканированию (адаптер есть и включён). */
    fun bluetoothReady(): Boolean {
        val a = adapter ?: return false
        return a.isEnabled
    }

    /**
     * Устройство по адресу — для прямого подключения без сканирования.
     * Наушники, занятые другим приложением (или молчащие в advertising),
     * часто всё же принимают прямое GATT-подключение по своему адресу.
     * @return null, если Bluetooth недоступен; сам адрес должен быть валиден
     */
    fun remoteDevice(address: String): android.bluetooth.BluetoothDevice? {
        val a = adapter ?: return null
        if (!a.isEnabled) return null
        return runCatching { a.getRemoteDevice(address) }.getOrNull()
    }

    /** Поток результатов сканирования; завершается после stopScan (закрытие коллектора). */
    fun scan(): Flow<ScanEvent> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled) {
            trySend(ScanEvent.Error("Bluetooth выключен или недоступен — включите его в шторке уведомлений"))
            close()
            return@callbackFlow
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            trySend(ScanEvent.Error("BLE-сканер недоступен (Bluetooth перезапускается?)"))
            close()
            return@callbackFlow
        }
        val scanAllowed = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        if (!scanAllowed) {
            trySend(ScanEvent.Error("Нет разрешения BLUETOOTH_SCAN — выдайте его и повторите"))
            close()
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters: List<ScanFilter> = emptyList()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScanEvent.Found(result.toScanItem()))
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "сканирование уже запущено"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "приложение не зарегистрировано в стеке BT"
                    SCAN_FAILED_INTERNAL_ERROR -> "внутренняя ошибка стека BT"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE-сканирование не поддерживается"
                    else -> "код $errorCode"
                }
                trySend(ScanEvent.Error("Сканирование прервано: $reason"))
                close()
            }
        }

        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure {
                trySend(ScanEvent.Error("Не удалось запустить сканирование: ${it.message}"))
                close()
                return@callbackFlow
            }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}

private fun ScanResult.toScanItem(): ScanItem {
    // Чтение имени требует BLUETOOTH_CONNECT — защищаемся на всякий случай.
    val name = runCatching { scanRecord?.deviceName ?: device.name }.getOrNull().orEmpty()
    return ScanItem(
        device = device,
        name = name,
        address = device.address,
        rssi = rssi,
    )
}
