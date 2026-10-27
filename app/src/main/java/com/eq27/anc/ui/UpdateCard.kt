package com.eq27.anc.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eq27.anc.update.UpdateManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * Карточка «Обновление приложения»: кнопка проверки релизов на GitHub,
 * скачивание APK с прогрессом и запуск установки.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var status by remember {
        mutableStateOf("Установлена версия ${UpdateManager.currentVersion()}")
    }
    var available by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }

    SectionCard("Обновление приложения") {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val release = available
        if (release != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Доступна новая версия: ${release.tagName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!release.hasApk) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "К этому релизу не приложен APK — обновление недоступно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (downloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Скачивание: $progress%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (release == null) {
                Button(
                    onClick = {
                        checking = true
                        busy = true
                        status = "Проверяем обновления…"
                        scope.launch {
                            when (val result = UpdateManager.checkLatest()) {
                                is UpdateManager.CheckResult.Available -> {
                                    available = result.release
                                    apkFile = null
                                    status = "Найдено обновление ${result.release.tagName}"
                                }

                                is UpdateManager.CheckResult.UpToDate ->
                                    status = "У вас последняя версия (${result.currentVersion})"

                                is UpdateManager.CheckResult.NoReleases ->
                                    status = result.message

                                is UpdateManager.CheckResult.Error ->
                                    status = "Ошибка проверки: ${result.message}"
                            }
                            checking = false
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Проверить обновления") }
            } else {
                Button(
                    onClick = {
                        val url = release.apkUrl ?: return@Button
                        downloading = true
                        busy = true
                        progress = 0
                        status = "Скачиваем ${release.apkName ?: "APK"}…"
                        scope.launch {
                            try {
                                val file = UpdateManager.downloadApk(context, url) { p ->
                                    progress = p
                                }
                                apkFile = file
                                downloading = false
                                status = "APK скачан — запускаем установку ${release.tagName}"
                                val started = UpdateManager.installApk(context, file)
                                status = if (started) {
                                    "Установщик запущен — подтвердите установку ${release.tagName}"
                                } else {
                                    "Разрешите установку из этого приложения в открывшихся " +
                                            "настройках, затем нажмите «Установить» ещё раз"
                                }
                            } catch (e: Exception) {
                                status = "Ошибка скачивания: ${e.message}"
                            } finally {
                                downloading = false
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && release.hasApk,
                ) { Text("Обновить версию") }

                if (apkFile != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            apkFile?.let {
                                val started = UpdateManager.installApk(context, it)
                                if (!started) {
                                    status = "Сначала разрешите установку в настройках системы"
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text("Установить") }
                }
            }

            if (checking) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
