plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Every commit is a build: the patch number is how many commits the branch
 * has. Nothing is bumped by hand, `versionCode` only ever grows, and an APK
 * can be traced back to the commit that made it (`git rev-list --count`).
 *
 * CI must check out with `fetch-depth: 0`, or the count is the depth of the
 * clone rather than the history; a build outside a git checkout gets 1.
 */
val commitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0).coerceAtLeast(1)

android {
    namespace = "io.github.dmitryweiner.synesthesia"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.dmitryweiner.synesthesia"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the ABIs the Rust core is built for (core/build.gradle.kts).
        // Without this, JNA's own 32-bit and MIPS natives would make the APK
        // installable on devices where the core cannot load.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // One debug key for every machine — each cloud session, CI, a laptop —
    // so a debug APK built anywhere installs over the previous one. It is
    // Android's standard debug key (password "android"), not a secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // So the screen can say which build it is, which is the point of a
        // version per commit.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
