import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


val cloudBaseUrl = System.getenv("TEMPLEARN_CLOUD_BASE_URL").orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
val cloudSyncKey = System.getenv("TEMPLEARN_CLOUD_SYNC_KEY").orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.tempalearn"
    compileSdk = 35

    // Generate BuildConfig explicitly for all Android variants.
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.tempalearn"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "0.10.12"
        buildConfigField("String", "TEMPLEARN_CLOUD_BASE_URL", "\"$cloudBaseUrl\"")
        buildConfigField("String", "TEMPLEARN_CLOUD_SYNC_KEY", "\"$cloudSyncKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
