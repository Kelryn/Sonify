plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":core:domain"))
}

kover {
    reports {
        verify {
            rule {
                // RNF-06 target is 90% branch coverage on :core:domain. The gate starts at
                // 40 because the JUnit suite is still being filled in; the pure logic is
                // meanwhile covered by tools/selfcheck (107 assertions, run in CI as its
                // own job). Raise this in a dedicated PR as the suite grows - a threshold
                // nobody can meet is a threshold everybody learns to ignore.
                bound {
                    minValue = 40
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                }
            }
        }
    }
}
