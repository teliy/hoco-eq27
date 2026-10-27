package com.eq27.anc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eq27.anc.MainViewModel
import com.eq27.anc.ble.ConnState
import com.eq27.anc.ble.ScanItem
import kotlinx.coroutines.delay

/**
 * Вкладка поиска и подключения к наушникам.
 */
@Composable
fun ConnectTab(vm: MainViewModel) {
    val scanning by vm.scanning.collectAsState()
    val items by vm.scanItems.collectAsState()
    val scanError by vm.scanError.collectAsState()
    val conn by vm.conn.collectAsState()
    val rawSeen by vm.rawSeen.collectAsState()

    var nameFilter by rememberSaveable { mutableStateOf("EQ27") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var addressText by rememberSaveable { mutableStateOf("") }
    var addressError by rememberSaveable { mutableStateOf<String?>(null) }
    var showEmptyEtherDiag by remember { mutableStateOf(false) }

    val connected = conn is ConnState.Connected

    // Диагностика пустого эфира: если скан идёт 12 с и стек не вернул НИ ОДНОГО
    // BLE-устройства — дело не в наушниках, а в стеке Bluetooth телефона.
    LaunchedEffect(scanning) {
        showEmptyEtherDiag = false
        if (scanning) {
            delay(12_000)
            if (rawSeen == 0) showEmptyEtherDiag = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        item {
            SectionCard("Поиск наушников") {
                OutlinedTextField(
                    value = nameFilter,
                    onValueChange = { nameFilter = it },
                    label = { Text("Фильтр имени") },
                    supportingText = { Text("Подстрока имени в advertising, например EQ27") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connected,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = showAll,
                        onClick = { showAll = !showAll },
                        label = { Text("Показывать все BLE") },
                        enabled = !connected,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (scanning) {
                        OutlinedButton(onClick = { vm.stopScan() }) { Text("Остановить") }
                    } else {
                        Button(
                            onClick = { vm.startScan(nameFilter, showAll) },
                            enabled = !connected,
                        ) { Text("Начать поиск") }
                    }
                }
                scanError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (scanning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Сканирование… BLE-устройств в эфире (без фильтра): $rawSeen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (showEmptyEtherDiag && scanning) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Эфир пустой: Bluetooth-менеджер отвечает, но не возвращает ни одного " +
                                "устройства — это сбой стека, а не проблема наушников. Помогает: " +
                                "выключить/включить Bluetooth (или на 10 с авиарежим), на части " +
                                "прошивок — включить Геолокацию, в крайнем случае перезагрузить телефон.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SectionCard("Подключиться по адресу (если скан молчит)") {
                Text(
                    "Наушники могут не вещать в advertising: их занял hoco.music в фоне, " +
                            "они уже подключены по классике (музыка/звонки) или «спят». " +
                            "Тогда прямое подключение по адресу срабатывает даже без сканирования.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addressText,
                    onValueChange = {
                        addressText = it
                        addressError = null
                    },
                    label = { Text("MAC-адрес наушников") },
                    placeholder = { Text("72:08:03:03:5A:6D") },
                    supportingText = { Text("Настройки → Bluetooth → HOCO EQ27 → «Подробнее»") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connected,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { addressError = vm.connectByAddress(addressText) },
                        enabled = !connected && addressText.isNotBlank(),
                    ) { Text("Подключиться напрямую") }
                }
                addressError?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!connected && items.isNotEmpty()) {
            item {
                SectionCard("Найдено устройств: ${items.size}") {
                    Text(
                        "Нажмите на устройство, чтобы подключиться по GATT.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items, key = { it.address }) { item ->
                DeviceRow(item) { vm.connect(item) }
            }
        }

        item {
            SectionCard("Подсказки") {
                HintLine(
                    "1. Достаньте наушники из кейса и наденьте их — BLE-канал должен проснуться.",
                )
                HintLine(
                    "2. Закройте приложение hoco.music: два приложения могут спорить за одну RX-линию.",
                )
                HintLine(
                    "3. Если наушники уже сопряжены с телефоном (медиаканал A2DP) — это нормально, " +
                            "нам нужен только параллельный BLE-канал управления.",
                )
                HintLine(
                    "4. Не находится? Попробуйте фильтр «HOCO» или включите «Показывать все BLE» — " +
                            "имя в advertising может отличаться от имени в настройках телефона.",
                )
            }
        }

        item {
            UpdateCard()
        }
    }
}

@Composable
private fun DeviceRow(item: ScanItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name.ifBlank { "(без имени)" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${item.rssi} dBm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun HintLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
