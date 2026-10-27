package com.eq27.anc.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eq27.anc.MainViewModel
import com.eq27.anc.ble.ConnState
import com.eq27.anc.ble.LogEntry
import com.eq27.anc.ble.propertiesText

/**
 * Корневой каркас: статус-баннер соединения + три вкладки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Eq27App(vm: MainViewModel) {
    val conn by vm.conn.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("EQ27 ANC — шумоподавление") })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(
                conn = conn,
                onRetry = { vm.reconnect() },
                onDisconnect = { vm.disconnect() },
            )
            TabRow(selectedTabIndex = tab) {
                listOf("Подключение", "Шумодав", "Лаборатория").forEachIndexed { i, title ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(title, maxLines = 1) },
                    )
                }
            }
            when (tab) {
                0 -> ConnectTab(vm)
                1 -> AncTab(vm)
                else -> LabTab(vm)
            }
        }
    }
}

// ------------------------------------------------------------------ баннер состояния

@Composable
fun ConnectionBanner(
    conn: ConnState,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (bg, text) = when (conn) {
        is ConnState.Connected -> Color(0xFF1E4634) to "● ${conn.name} · MTU ${conn.mtu}"
        is ConnState.Connecting -> Color(0xFF4A4118) to "⏳ Подключение к ${conn.name} (попытка ${conn.attempt})…"
        is ConnState.Failed -> Color(0xFF52201C) to "✕ ${conn.message}"
        ConnState.Idle -> Color(0xFF232B36) to "Не подключено"
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                // Текст подсказки при ошибке должен быть виден целиком — не обрезаем
                maxLines = if (conn is ConnState.Failed) 10 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (conn) {
                is ConnState.Connected -> TextButton(onClick = onDisconnect) { Text("Отключить") }
                is ConnState.Failed -> TextButton(onClick = onRetry) { Text("Повторить") }
                else -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------ общие компоненты

/** Карточка-секция с заголовком. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Кнопка с выпадающим списком — простая замена сложных dropdown-компонентов. */
@Composable
fun <T> SelectorButton(
    label: String,
    options: List<T>,
    selected: T?,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                if (selected != null) optionText(selected) else label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Моноширинный текст для HEX. */
@Composable
fun HexText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 2,
) {
    Text(
        value,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Полный список журнала с кнопками копирования и очистки. */
@Composable
fun LogPanel(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun copyLines(text: String, toast: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Журнал (${entries.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (entries.isEmpty()) {
                    Toast.makeText(context, "Журнал пуст — копировать нечего", Toast.LENGTH_SHORT).show()
                } else {
                    copyLines(
                        entries.joinToString("\n") { "${it.time} [${it.level}] ${it.message}" },
                        "Скопировано строк: ${entries.size}",
                    )
                }
            }) { Text("Копировать") }
            TextButton(onClick = onClear) { Text("Очистить") }
        }
        if (entries.isEmpty()) {
            Text(
                "Пока пусто. Здесь появятся события сканирования, подключения, TX/RX. " +
                        "Тап по строке — копирует её.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        MaterialTheme.colorScheme.background,
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                items(entries) { entry ->
                    LogRow(entry) { line ->
                        copyLines(line, "Строка скопирована")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, onCopyLine: (String) -> Unit) {
    val color = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> Color(0xFFFDD663)
        "T" -> MaterialTheme.colorScheme.primary
        "R" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val line = "${entry.time} [${entry.level}] ${entry.message}"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCopyLine(line) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            entry.time,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            entry.message,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
