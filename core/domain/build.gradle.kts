plugins {
    alias(libs.plugins.kotlin.jvm)
    // Required here, not only in the root: the `kover(project(...))` aggregation in the
    // root build consumes artifact variants that only the plugin produces in the module
    // that is being measured.
    alias(libs.plugins.kover)
}

// ─────────────────────────────────────────────────────────────────────────────
//  :core:domain is PURE KOTLIN/JVM. It must never depend on the Android SDK.
//  This is what makes the decision-making core of the app verifiable without
//  an Android toolchain (see docs/03-PLAN-DESARROLLO.md, section 1).
//  CI enforces it with a "purity guard" step that fails on `import android.`.
// ─────────────────────────────────────────────────────────────────────────────

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Off until CI has produced a first green build to compare against: with no
        // local Android toolchain, a single new warning in a future compiler release would
        // turn every push into a blind debugging round trip.
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    // No coroutines on purpose: the module must stay compilable with a bare `kotlinc`
    // and no classpath, which is what the local self-check and the CI `selfcheck` job do.

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
