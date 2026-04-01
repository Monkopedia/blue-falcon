plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.android.library")
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

android {
    compileSdk = 33
    namespace = "dev.bluefalcon.integration"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    lint {
        disable += "MissingPermission"
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

kotlin {
    jvmToolchain(17)
    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon:2.5.4")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("androidx.test:runner:1.5.2")
                implementation("androidx.test:rules:1.5.0")
                implementation("androidx.test.ext:junit:1.1.5")
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.5.2")
                implementation("androidx.test:rules:1.5.0")
                implementation("androidx.test.ext:junit:1.1.5")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                implementation(kotlin("test-junit"))
            }
        }
    }
}
