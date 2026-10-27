package com.eq27.anc.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat

/** Мгновенный снимок состояния Bluetooth-окружения приложения. */
private data class BtDiag(
    val adapterPresent: Boolean,
    val btEnabled: Boolean,
    val scanGranted: Boolean,
    val connectGranted: Boolean,
    val leScannerAvailable: Boolean,
    val locationEnabled: Boolean,
) {
    val allReady: Boolean
        get() = adapterPresent && btEnabled && scanGranted && connectGranted && leScannerAvailable
}

private fun inspect(context: Context): BtDiag {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bm?.adapter
    val btEnabled = adapter != null && runCatching { adapter.isEnabled }.getOrDefault(false)
    val leScanner = btEnabled &&
            runCatching { adapter?.bluetoothLeScanner != null }.getOrDefault(false)
    val location = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }.getOrDefault(false)
    return BtDiag(
        adapterPresent = adapter != null,
        btEnabled = btEnabled,
        scanGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED,
        connectGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED,
        leScannerAvailable = leScanner,
        locationEnabled = location,
    )
}

/**
 * Диагностическая карточка «Состояние Bluetooth»: адаптер, разрешения,
 * BLE-сканер и геолокация. Обновляется при возврате на экран и по кнопке,
 * чтобы сразу было видно, почему скан молчит.
 */
@Composable
fun BtStatusCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var diag by remember { mutableStateOf(inspect(context)) }

    // Перечитываем состояние при каждом возврате на экран:
    // пользователь мог включить Bluetooth или выдать разрешение в настройках.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) diag = inspect(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(title = "Состояние Bluetooth", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    diag.allReady -> "Всё готово к сканированию"
                    !diag.adapterPresent -> "Bluetooth-адаптер не найден"
                    !diag.btEnabled -> "Bluetooth выключен"
                    !diag.scanGranted || !diag.connectGranted -> "Не хватает разрешений"
                    else -> "BLE-сканер недоступен"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (diag.allReady) Color(0xFF3FA46A) else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { diag = inspect(context) }) { Text("Обновить") }
        }

        DiagLine(
            label = when {
                diag.adapterPresent && diag.btEnabled -> "Адаптер Bluetooth: включён"
                diag.adapterPresent -> "Адаптер Bluetooth: выключен"
                else -> "Адаптер Bluetooth: отсутствует"
            },
            ok = diag.adapterPresent && diag.btEnabled,
        )
        DiagLine(
            label = "Разрешение BLUETOOTH_SCAN: " + if (diag.scanGranted) "выдано" else "НЕ выдано",
            ok = diag.scanGranted,
        )
        DiagLine(
            label = "Разрешение BLUETOOTH_CONNECT: " + if (diag.connectGranted) "выдано" else "НЕ выдано",
            ok = diag.connectGranted,
        )
        DiagLine(
            label = when {
                !diag.btEnabled -> "BLE-сканер: недоступен (Bluetooth выключен)"
                diag.leScannerAvailable -> "BLE-сканер: доступен"
                else -> "BLE-сканер: недоступен (стек перезапускается?)"
            },
            ok = diag.leScannerAvailable,
        )
        DiagLine(
            label = "Геолокация: " + if (diag.locationEnabled) "включена" else "выключена",
            ok = diag.locationEnabled,
            optional = true,
        )

        if (!diag.allReady) {
            Spacer(Modifier.height(6.dp))
            val hint = buildString {
                if (!diag.scanGranted || !diag.connectGranted) {
                    append("Разрешения выдаются при первом запуске; если окно уже пропало — ")
                    append("Настройки → Приложения → EQ27 ANC → Разрешения → Bluetooth. ")
                }
                if (diag.btEnabled && !diag.leScannerAvailable) {
                    append("BLE-сканер недоступен: выключите и включите Bluetooth, подождите 5–10 с. ")
                }
                if (!diag.locationEnabled) {
                    append("На части прошивок (MIUI, EMUI и др.) скан молчит без геолокации — ")
                    append("попробуйте включить её в шторке. ")
                }
            }
            Text(
                hint.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "Если скан всё равно молчит и «в эфире» 0 устройств — проблема " +
                        "в стеке Bluetooth телефона (помогает выключение/включение Bluetooth " +
                        "или 10 с авиарежима), а не в наушниках.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagLine(label: String, ok: Boolean, optional: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ok) "✓" else if (optional) "○" else "✕",
            style = MaterialTheme.typography.labelLarge,
            color = when {
                ok -> Color(0xFF3FA46A)
                optional -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.width(20.dp),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
