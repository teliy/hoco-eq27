package com.eq27.anc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eq27.anc.ui.Eq27App
import com.eq27.anc.ui.theme.Eq27Theme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Eq27Theme {
                val vm: MainViewModel = viewModel()
                var granted by remember { mutableStateOf(hasPermissions()) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    granted = result.values.all { it }
                }

                // Если пользователь выдал разрешения в настройках системы и вернулся —
                // подхватываем это без перезапуска приложения.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) granted = hasPermissions()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(Unit) {
                    if (!granted) launcher.launch(PERMISSIONS)
                }

                if (granted) {
                    Eq27App(vm)
                } else {
                    PermissionRationale(
                        onRetry = { launcher.launch(PERMISSIONS) },
                    )
                }
            }
        }
    }

    private fun hasPermissions(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** На Android 12+ достаточно этих двух разрешений (minSdk = 31). */
        private val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}

@Composable
private fun PermissionRationale(onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Нужны разрешения Bluetooth",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Приложению нужны BLUETOOTH_SCAN и BLUETOOTH_CONNECT, " +
                    "чтобы находить наушники HOCO EQ27 и обмениваться с ними командами. " +
                    "Геолокация не используется.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Выдать разрешения") }
        Spacer(Modifier.height(8.dp))
        // Если диалог уже был отклонён с «Больше не спрашивать», повторный запуск
        // ничего не даст — остаётся только системный экран разрешений.
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }) { Text("Открыть настройки приложения") }
    }
}
