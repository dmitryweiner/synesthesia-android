import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Every commit is a build: the patch number is how many commits the branch
 * has *since this release line opened*. Nothing is bumped by hand except the
 * line itself, `versionCode` only ever grows, and an APK can be traced back
 * to the commit that made it (`git rev-list --count`).
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

/**
 * The release line, bumped by hand when the app has changed enough to say so,
 * and the commit count at which it opened — so the first build of the line is
 * `x.y.0` and the ones after it count up. 0.2 opened with the app speaking
 * four languages; 0.1 was the six phases of PLAN.md, 37 commits of it.
 */
val releaseLine = "0.2"
val lineOpenedAt = 38

android {
    namespace = "io.github.dmitryweiner.synesthesia"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.dmitryweiner.synesthesia"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "$releaseLine.${(commitCount - lineOpenedAt).coerceAtLeast(0)}"
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

    // Android 13+ lets the user pick an app's language on its own; the list
    // comes from the values-* folders (res/resources.properties says which
    // language values/ itself is).
    androidResources {
        generateLocaleConfig = true
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

/**
 * The recorded samples the points' layers play (synesthesia-core
 * PLAN-SAMPLES-SCRIPTS.md S7): synesthesia-samples' release asset, pinned by
 * tag and SHA-256 here, downloaded once into the build and packed as the
 * asset `bank.bin` — never committed. Bump both together for a new bank.
 */
val bankUrl = "https://github.com/dmitryweiner/synesthesia-samples/releases/download/bank-v1/bank.bin"
val bankSha256 = "c6ec462a3e057b41ca82a918427f7d7be4efd1c0a437f80a628e2f1cac9bd335"

abstract class FetchBank : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val file = outputDir.get().file("bank.bin").asFile
        fun hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (file.exists() && hex(file.readBytes()) == sha256.get()) return
        val bytes = URI(url.get()).toURL().readBytes()
        check(hex(bytes) == sha256.get()) { "${url.get()}: SHA-256 ${hex(bytes)}, pinned ${sha256.get()}" }
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }
}

val fetchBank = tasks.register<FetchBank>("fetchBank") {
    description = "Downloads the sample bank (bank.bin) by its pinned tag and SHA-256."
    url.set(bankUrl)
    sha256.set(bankSha256)
    outputDir.set(layout.buildDirectory.dir("generated/bank"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchBank, FetchBank::outputDir)
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
