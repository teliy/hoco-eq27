package com.eq27.anc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eq27.anc.AncMode
import com.eq27.anc.MainViewModel
import com.eq27.anc.ble.ConnState
import com.eq27.anc.ble.GattClient
import com.eq27.anc.ble.RxPacket

/**
 * Вкладка управления шумоподавлением.
 * Три больших режима: ANC выкл / ANC вкл / прозрачный режим.
 * Кадры команд пользователь получает перехватом из hoco.music (см. README)
 * и сохраняет через «Изменить кадры».
 */
@Composable
fun AncTab(vm: MainViewModel) {
    val conn by vm.conn.collectAsState()
    val ancOn by vm.ancOn.collectAsState()
    val ancOff by vm.ancOff.collectAsState()
    val transparency by vm.transparency.collectAsState()
    val recentRx by vm.recentRx.collectAsState()

    var editing by remember { mutableStateOf(false) }
    var editingOn by remember { mutableStateOf(ancOn) }
    var editingOff by remember { mutableStateOf(ancOff) }
    var editingTr by remember { mutableStateOf(transparency) }

    val connected = conn is ConnState.Connected

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        item {
            SectionCard("Режим шумоподавления") {
                if (!connected) {
                    Text(
                        "Сначала подключитесь к наушникам на вкладке «Подключение».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeCard(AncMode.OFF, ancOff, Modifier.weight(1f)) { vm.applyAnc(AncMode.OFF) }
                        ModeCard(AncMode.ON, ancOn, Modifier.weight(1f)) { vm.applyAnc(AncMode.ON) }
                        ModeCard(AncMode.TRANSPARENCY, transparency, Modifier.weight(1f)) {
                            vm.applyAnc(AncMode.TRANSPARENCY)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    editingOn = ancOn
                    editingOff = ancOff
                    editingTr = transparency
                    editing = true
                }) { Text("Изменить кадры режимов") }
            }
        }

        item {
            SectionCard("Последние входящие пакеты") {
                val recent = recentRx.takeLast(8).reversed()
                if (recent.isEmpty()) {
                    Text(
                        "Пока ничего. Когда наушники что-то пришлют (например, ответ на команду) — появится здесь и в «Журнале».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recent.forEach { rx ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                GattClient.shortUuid(rx.uuid),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            HexText(
                                rx.bytes.hex(),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Как получить кадры команд") {
                HintLine("1. Включите в настройках разработчика «Журнал отслеживания Bluetooth HCI».")
                HintLine("2. Откройте hoco.music и переключите ANC: выкл → ANC → прозрачный → выкл.")
                HintLine("3. Снимите btsnoop_hci.log, откройте в Wireshark, фильтр btatt — и найдите кадры FE DC BA.")
                HintLine("4. Скопируйте их сюда через «Изменить кадры режимов». Подробная инструкция — в README проекта.")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Совет: кадры можно не пересчитывать — вставляйте «как есть», приложение шлёт байты без изменений.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Кадры режимов ANC") },
            text = {
                Column {
                    HexField("ANC выключен", editingOff) { editingOff = it }
                    Spacer(Modifier.height(8.dp))
                    HexField("ANC включён", editingOn) { editingOn = it }
                    Spacer(Modifier.height(8.dp))
                    HexField("Прозрачный режим", editingTr) { editingTr = it }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Пример: FE DC BA 04 00 74 01 00 xx xx — вставляйте кадры из Wireshark целиком.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setAncPreset(AncMode.OFF, editingOff.trim())
                    vm.setAncPreset(AncMode.ON, editingOn.trim())
                    vm.setAncPreset(AncMode.TRANSPARENCY, editingTr.trim())
                    editing = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ModeCard(mode: AncMode, hex: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                mode.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            if (hex.isBlank()) {
                Text(
                    "кадр не задан",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                HexText(hex, maxLines = 1, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun HexField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

