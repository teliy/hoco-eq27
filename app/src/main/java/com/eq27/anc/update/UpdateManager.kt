package com.eq27.anc.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.eq27.anc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения через GitHub Releases репозитория [REPO_URL].
 *
 * Сценарий:
 * 1. Пользователь нажимает «Проверить обновления» → [checkLatest] читает последний
 *    релиз через API GitHub (`/releases/latest`) и сравнивает его тег с текущей
 *    версией приложения ([BuildConfig.VERSION_NAME]).
 * 2. Если есть релиз новее — [downloadApk] скачивает APK-ассет релиза во
 *    внутренний кэш (`cacheDir/updates/`) с показом прогресса.
 * 3. [installApk] передаёт скачанный файл системному установщику через
 *    FileProvider. При отсутствии разрешения «Установка неизвестных приложений»
 *    сначала открываются настройки системы.
 *
 * Чтобы обновление установилось, APK в релизе должен быть подписан тем же ключом,
 * что и установленная версия — ключ из `keystore/eq27-release.jks` используется
 * и локально, и в GitHub Actions (см. README).
 */
object UpdateManager {

    const val REPO_URL: String = "https://github.com/teliy/hoco-eq27"
    private const val API_LATEST: String =
        "https://api.github.com/repos/teliy/hoco-eq27/releases/latest"
    private const val USER_AGENT: String = "EQ27-ANC-App"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Краткая сводка по последнему релизу. */
    data class ReleaseInfo(
        val tagName: String,   // например "v1.1.0"
        val version: String,   // "1.1.0" — без ведущей "v"
        val notes: String,     // описание релиза (body), может быть пустым
        val apkUrl: String?,   // ссылка на APK-ассет, null если APK не приложен
        val apkName: String?,
        val apkSize: Long,     // размер в байтах
    ) {
        val hasApk: Boolean get() = apkUrl != null
    }

    /** Итог проверки обновлений. */
    sealed class CheckResult {
        /** Обновление не требуется — установлена последняя версия. */
        data class UpToDate(val currentVersion: String) : CheckResult()

        /** Доступна более новая версия. */
        data class Available(val release: ReleaseInfo) : CheckResult()

        /** В репозитории ещё нет ни одного релиза. */
        data class NoReleases(val message: String) : CheckResult()

        /** Сетевая ошибка или неожиданный ответ API. */
        data class Error(val message: String) : CheckResult()
    }

    // ------------------------------------------------------------------ проверка

    /** Читает последний релиз из GitHub и сравнивает с текущей версией. */
    suspend fun checkLatest(): CheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = openGet(API_LATEST)
            val code = conn.responseCode
            if (code == 404) {
                return@withContext CheckResult.NoReleases("В репозитории ещё нет релизов")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext CheckResult.Error("GitHub API вернул код $code")
            }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (tag.isBlank()) {
                return@withContext CheckResult.NoReleases("В репозитории ещё нет релизов")
            }

            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkName = name
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            val info = ReleaseInfo(
                tagName = tag,
                version = tag.trim().removePrefix("v").removePrefix("V"),
                notes = json.optString("body", "").take(600),
                apkUrl = apkUrl?.takeIf { it.isNotBlank() },
                apkName = apkName,
                apkSize = apkSize,
            )

            val current = currentVersion()
            if (compareVersions(info.version, current) > 0) {
                CheckResult.Available(info)
            } else {
                CheckResult.UpToDate(current)
            }
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Текущая версия приложения из BuildConfig. */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Сравнивает версии вида "1.2.3" (ведущие "v" допускаются).
     * @return -1, если a < b; 0, если равны; +1, если a > b
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> = v.trim()
            .removePrefix("v").removePrefix("V")
            .split('.')
            .map { seg -> seg.filter { ch -> ch.isDigit() }.ifEmpty { "0" }.toInt() }

        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    // ------------------------------------------------------------------ скачивание

    /**
     * Скачивает APK во внутренний кэш: `cacheDir/updates/eq27-update.apk`.
     * Старые скачанные файлы предварительно удаляются.
     *
     * @param onProgress прогресс скачивания 0..100
     * @return скачанный файл
     * @throws IOException при сетевой ошибке или пустом ответе
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "eq27-update.apk")

        val conn = openGet(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Сервер вернул код $code")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            if (outFile.length() == 0L) throw IOException("Скачан пустой файл")
            outFile
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ установка

    /**
     * Запускает системный установщик для скачанного APK.
     * Если разрешение «Установка неизвестных приложений» ещё не выдано —
     * открывает соответствующий экран настроек и возвращает false
     * (после выдачи разрешения нужно нажать кнопку установки ещё раз).
     *
     * @return true, если установщик запущен
     */
    fun installApk(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ утилиты

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return conn
    }
}
