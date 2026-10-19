plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kz.tenir.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.tenir.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // для пилота подписываем отладочным ключом; для эксплуатации задайте свой signingConfig
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // QUERY_ALL_PACKAGES не используется; SYSTEM_ALERT_WINDOW — осознанно, для автозапуска
        disable += "ProtectedPermissions"
    }
}

dependencies {
    implementation(project(":core"))

    // Robolectric: экраны, фокус пульта и рендер 1080p/4K на JVM (src/test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
