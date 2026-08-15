// Kotlin DSL scripts do not carry java.security in their implicit imports, and the plugins
// block has to be the first statement, so this cannot be a top-level constant either.
import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ritmute.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ritmute.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The keystore is written by CI from a repository secret and is never committed. It is
    // absent on a fresh clone and on pull requests from forks, where secrets are withheld,
    // so its absence has to be a fallback rather than an error: `assembleRelease` still has
    // to run there for the APK size budget to mean anything.
    val keystore = rootProject.file("release-keystore.p12")
    val keystorePassword = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").orNull

    signingConfigs {
        if (keystore.exists() && !keystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = keystore
                storeType = "PKCS12"
                storePassword = keystorePassword
                keyPassword = keystorePassword
                // Read back out of the file rather than hard-coded. The store was not
                // produced by keytool, so its alias is whatever the exporting tool wrote;
                // a guess that does not match fails during signing, which is the most
                // expensive place in the pipeline to discover it.
                keyAlias = KeyStore.getInstance("PKCS12").run {
                    keystore.inputStream().use { load(it, keystorePassword.toCharArray()) }
                    aliases().nextElement()
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The debug key is public and identical on every machine on earth, which is
            // exactly why Play Protect treats a build signed with it more harshly than one
            // signed with an unknown but unique key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // Replaces the deprecated `resourceConfigurations`, removed in AGP 9.
        localeFilters += listOf("en", "es")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        error += listOf("MissingTranslation", "ExtraTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:system"))
    implementation(project(":core:ui"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:tools"))

    // Declared explicitly rather than leaked transitively through :core:ui's `api`.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

