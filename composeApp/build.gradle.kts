import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("io.ktor:ktor-client-core:3.0.1")
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.1")
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.work:work-runtime-ktx:2.9.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
            // skeniranje barkoda (Google code scanner - ne treba dozvola za kameru)
            implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
            // GPS
            implementation("com.google.android.gms:play-services-location:21.3.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.1")
        }
    }
}

android {
    namespace = "com.gakovic.etikete"
    compileSdk = 34

    defaultConfig {
        // isti applicationId i potpis kao ranije - nova verzija se instalira preko stare
        applicationId = "com.gakovic.etikete"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "etikete2213"
            keyAlias = "etikete"
            keyPassword = "etikete2213"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
