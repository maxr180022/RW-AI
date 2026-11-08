plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Подпись release-сборки берётся ТОЛЬКО из переменных окружения (секреты не хранятся в репозитории).
val releaseKeystorePath: String? = System.getenv("RWAI_KEYSTORE_FILE")
val releaseConfigured = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

// Постоянный ключ подписи для debug-сборок: без него каждая облачная сборка получает новый случайный ключ,
// и Android не даёт обновить приложение поверх предыдущей версии. Путь и пароль задаёт только CI.
val stableKeystorePath: String? = System.getenv("RWAI_STABLE_KEYSTORE")
val stablePassword: String? = System.getenv("RWAI_KEY_PASSPHRASE")
val stableConfigured = !stableKeystorePath.isNullOrBlank() && !stablePassword.isNullOrBlank() && file(stableKeystorePath).exists()

android {
    namespace = "com.rwai.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rwai.agent"
        minSdk = 26
        targetSdk = 35
        // Растёт с каждой облачной сборкой, чтобы новая версия всегда считалась обновлением.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.5.5"
        // Только реальные телефоны (экономит ~20 МБ: нативная библиотека распознавания речи)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (stableConfigured) {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storePassword = stablePassword
                keyAlias = "rwai"
                keyPassword = stablePassword
            }
        }
        if (releaseConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("RWAI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RWAI_KEY_ALIAS")
                keyPassword = System.getenv("RWAI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseConfigured) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // С постоянным ключом приложение обновляется поверх прежней версии без переустановки и повторной выдачи разрешений.
            if (stableConfigured) signingConfig = signingConfigs.getByName("stable")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/mailcap", "META-INF/mimetypes.default", "META-INF/javamail.*")
        jniLibs { useLegacyPackaging = true } // JNA/Vosk загружают библиотеки по имени
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Офлайн-распознавание речи для «Окей, Руся» (Vosk) и его зависимость JNA
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
