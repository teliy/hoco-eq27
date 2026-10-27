plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Ключ подписи лежит прямо в репозитории (keystore/eq27-release.jks), чтобы:
 *  - локальные сборки в Android Studio,
 *  - и APK, собранные GitHub Actions в релизах,
 * имели ОДНУ подпись — иначе Android не даст установить обновление
 * поверх приложения с другим ключом.
 *
 * Пароль публичный намеренно: обновления приходят только из релизов
 * https://github.com/teliy/hoco-eq27, куда пушите только вы.
 * Для «серийного» приложения секретный ключ лучше вынести в секреты CI.
 */
val eq27Keystore = rootProject.file("keystore/eq27-release.jks")

android {
    namespace = "com.eq27.anc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eq27.anc"
        minSdk = 31          // Android 12
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"   // семантическая версия: сравнивается с тегом релиза (v1.0.0)
    }

    signingConfigs {
        if (eq27Keystore.exists()) {
            create("eq27") {
                storeFile = eq27Keystore
                storePassword = "eq27anc"
                keyAlias = "eq27"
                keyPassword = "eq27anc"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (eq27Keystore.exists()) {
                signingConfig = signingConfigs.getByName("eq27")
            }
        }
        debug {
            // Тот же ключ, чтобы debug-сборка обновлялась из релизов и наоборот
            if (eq27Keystore.exists()) {
                signingConfig = signingConfigs.getByName("eq27")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.VERSION_NAME нужен UpdateManager
    }
}

dependencies {
    // Jetpack Compose (версии выравниваются через BOM)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity / жизненный цикл
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Ядро Android
    implementation("androidx.core:core-ktx:1.13.1")

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
