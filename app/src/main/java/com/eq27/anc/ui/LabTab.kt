package com.eq27.anc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eq27.anc.MainViewModel
import com.eq27.anc.ble.ConnState
import com.eq27.anc.ble.GattTable
import com.eq27.anc.ble.propertiesText
import com.eq27.anc.protocol.CrcVariant
import com.eq27.anc.protocol.CustomCommand
import com.eq27.anc.protocol.parseHexLoose

/**
 * Вкладка-лаборатория: здесь можно слать ЛЮБЫЕ байты, конструировать RCSP-кадры,
 * проверять CRC и изучать GATT-таблицу наушников.
 */
@Composable
fun LabTab(vm: MainViewModel) {
    val conn by vm.conn.collectAsState()
    val table by vm.table.collectAsState()
    val writeChar by vm.writeChar.collectAsState()
    val notifyChar by vm.notifyChar.collectAsState()
    val writeNoResponse by vm.writeNoResponse.collectAsState()
    val chunkSize by vm.chunkSize.collectAsState()
    val crcVariant by vm.crcVariant.collectAsState()
    val crcIncludeLen by vm.crcIncludeLen.collectAsState()
    val custom by vm.customCommands.collectAsState()
    val logEntries by vm.log.collectAsState()

    val connected = conn is ConnState.Connected

    var hexInput by rememberSaveable { mutableStateOf("") }
    var cmdInput by rememberSaveable { mutableStateOf("") }
    var payloadInput by rememberSaveable { mutableStateOf("") }
    var solverInput by rememberSaveable { mutableStateOf("") }
    var solverResult by rememberSaveable { mutableStateOf("") }
    var chunkInput by rememberSaveable { mutableStateOf(if (chunkSize == 0) "" else chunkSize.toString()) }
    var addDialog by remember { mutableStateOf(false) }
    var newCmdName by remember { mutableStateOf("") }
    var newCmdHex by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        // ------------------------------------------------ отправка HEX
        item {
            SectionCard("Отправить HEX (как есть)") {
                if (!connected) {
                    Warning("Подключитесь к наушникам — кнопки отправки станут активными.")
                }
                SelectorButton(
                    label = "TX-характеристика (авто)",
                    options = table?.writeCandidates ?: emptyList(),
                    selected = writeChar,
                    optionText = { GattShort(it) },
                    onSelect = { vm.setWriteTarget(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("Кадр в HEX") },
                    placeholder = { Text("FE DC BA 04 00 74 01 00 xx xx") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.sendHex(hexInput) },
                    enabled = connected && hexInput.parseHexLoose() != null,
                ) { Text("Отправить") }
            }
        }

        // ------------------------------------------------ конструктор RCSP
        item {
            SectionCard("Конструктор кадра RCSP") {
                Text(
                    "FE DC BA | len(2) | cmd(2, LE) | payload | CRC(2, LE)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cmdInput,
                        onValueChange = { cmdInput = it },
                        label = { Text("Команда") },
                        placeholder = { Text("7401") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = payloadInput,
                        onValueChange = { payloadInput = it },
                        label = { Text("Payload") },
                        placeholder = { Text("01 00") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(2f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectorButton(
                        label = "Вариант CRC",
                        options = CrcVariant.entries.toList(),
                        selected = crcVariant,
                        optionText = { it.label },
                        onSelect = { vm.setCrcVariant(it) },
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = crcIncludeLen,
                            onCheckedChange = { vm.setCrcIncludeLen(it) },
                        )
                        Text("CRC по len+data", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val frame = vm.buildFrame(cmdInput, payloadInput)
                        if (frame != null) hexInput = frame
                    },
                ) { Text("Собрать кадр → в поле «Отправить HEX»") }
            }
        }

        // ------------------------------------------------ решатель CRC
        item {
            SectionCard("Решатель CRC") {
                Text(
                    "Вставьте кадр, захваченный из hoco.music, — приложение определит, " +
                            "какой вариант CRC использует ваша прошивка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = solverInput,
                    onValueChange = { solverInput = it },
                    label = { Text("Захваченный кадр") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { solverResult = vm.solveCrc(solverInput) }) {
                    Text("Анализировать")
                }
                if (solverResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        solverResult,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ------------------------------------------------ мои команды
        item {
            SectionCard("Мои команды (${custom.size})") {
                if (custom.isEmpty()) {
                    Warning(
                        "Пусто. Сохраняйте сюда полезные кадры (например, те же ANC-команды), " +
                            "чтобы жать их одной кнопкой.",
                    )
                } else {
                    custom.forEachIndexed { idx, cmd ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cmd.name, style = MaterialTheme.typography.bodyMedium)
                                HexText(cmd.hex, maxLines = 1)
                            }
                            TextButton(
                                onClick = { vm.sendHex(cmd.hex) },
                                enabled = connected,
                            ) { Text("Шлю") }
                            TextButton(onClick = { vm.removeCustom(idx) }) { Text("✕") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { addDialog = true }) { Text("Добавить команду") }
            }
        }

        // ------------------------------------------------ параметры отправки
        item {
            SectionCard("Параметры отправки") {
                SwitchRow(
                    "WRITE_NO_RESPONSE (быстрая запись без подтверждения)",
                    writeNoResponse,
                ) { vm.setWriteNoResponse(it) }
                OutlinedTextField(
                    value = chunkInput,
                    onValueChange = { v ->
                        chunkInput = v
                        vm.setChunkSize(v.toIntOrNull() ?: 0)
                    },
                    label = { Text("Кусок, байт (пусто = авто от MTU)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SelectorButton(
                    label = "RX-характеристика (авто)",
                    options = table?.notifyCandidates ?: emptyList(),
                    selected = notifyChar,
                    optionText = { GattShort(it) },
                    onSelect = { vm.setNotifyTarget(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ------------------------------------------------ дамп GATT
        item {
            SectionCard("Дамп GATT") {
                val t = table
                if (t == null) {
                    Warning("Подключитесь к наушникам, чтобы увидеть их сервисы и характеристики.")
                } else {
                    GattDump(t, writeChar, notifyChar)
                }
            }
        }

        // ------------------------------------------------ журнал
        item {
            SectionCard("Журнал") {
                LogPanel(
                    entries = logEntries,
                    onClear = { vm.clearLog() },
                )
            }
        }
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Новая команда") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCmdName,
                        onValueChange = { newCmdName = it },
                        label = { Text("Название") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newCmdHex,
                        onValueChange = { newCmdHex = it },
                        label = { Text("HEX кадра") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newCmdHex.parseHexLoose() != null) {
                            vm.addCustom(newCmdName, newCmdHex.trim())
                            newCmdName = ""
                            newCmdHex = ""
                            addDialog = false
                        }
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Отмена") } },
        )
    }
}

// ------------------------------------------------------------------ компоненты

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun GattShort(uuid: String): String =
    "0x" + GattClientShort(uuid) + if (uuid.length > 8) "…" else ""

private fun GattClientShort(uuid: String): String =
    uuid.removePrefix("0000").take(4).uppercase()

@Composable
private fun GattDump(table: GattTable, writeSel: String?, notifySel: String?) {
    Column {
        Text(
            "TX-кандидаты — характеристики с WRITE; RX — с NOTIFY. " +
                    "Автовыбор уже подсвечен, можно переопределить в «Параметрах отправки».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        table.services.forEach { svc ->
            Text(
                "Сервис 0x${svc.uuid.removePrefix("0000").take(4).uppercase()}  ${svc.uuid}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            svc.characteristics.forEach { c ->
                val marks = buildList {
                    if (c.uuid == writeSel) add("TX")
                    if (c.uuid == notifySel) add("RX")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 2.dp),
                ) {
                    Text(
                        (if (marks.isNotEmpty()) "[${marks.joinToString(",")}] " else "") +
                                "0x${c.uuid.removePrefix("0000").take(4).uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (marks.isNotEmpty()) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(150.dp),
                    )
                    Text(
                        propertiesText(c.properties),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
