// The shared Rust core (synesthesia-core, pinned in rust/Cargo.toml) as an
// Android library: the native library for every ABI the app ships, and the
// Kotlin bindings UniFFI generates from it (PLAN.md decisions 1–2).
//
// Three tasks, all plain `cargo` calls so the build is visible from a shell:
//   cargoBuildAndroid  cargo ndk … build --release   → jniLibs/<abi>/libsyn_android.so
//   cargoBuildHost     cargo build --release         → the same library for this machine,
//                                                      loaded by the JVM unit tests
//   uniffiBindings     uniffi-bindgen generate       → Kotlin, from the host library
//
// The Rust side is always built in release: the DSP is per-sample, and an
// unoptimized core cannot play in real time.
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

/** ABIs the app ships: phones, and the emulator on a PC. */
val rustAbis = listOf("arm64-v8a", "x86_64")
val rustRoot: Directory = layout.projectDirectory.dir("rust")
val hostLibName: String = System.mapLibraryName("syn_android")

android {
    namespace = "io.github.dmitryweiner.synesthesia.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Every input of a cargo build: the manifests, the lockfile and the sources — not `target/`. */
fun rustSources(): ConfigurableFileCollection =
    files(fileTree(rustRoot) { exclude("target/**") })

abstract class CargoNdkBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rustDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val targets = abis.get().flatMap { listOf("-t", it) }
        exec.exec {
            workingDir = rustDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            commandLine(
                listOf("cargo", "ndk") + targets +
                    listOf("-P", minSdk.get().toString(), "-o", out.absolutePath) +
                    listOf("build", "--release", "-p", "syn-android"),
            )
        }
    }
}

abstract class CargoHostBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rustDir: DirectoryProperty

    @get:OutputFile
    abstract val library: RegularFileProperty

    @TaskAction
    fun build() {
        exec.exec {
            workingDir = rustDir.get().asFile
            commandLine("cargo", "build", "--release", "-p", "syn-android")
        }
    }
}

abstract class UniffiBindings @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:Internal
    abstract val rustDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        exec.exec {
            workingDir = rustDir.get().asFile
            commandLine(
                "cargo", "run", "--release", "-q", "-p", "uniffi-bindgen", "--",
                "generate", "--no-format", "--language", "kotlin",
                "--library", library.get().asFile.absolutePath,
                "--out-dir", out.absolutePath,
            )
        }
    }
}

val cargoBuildAndroid = tasks.register<CargoNdkBuild>("cargoBuildAndroid") {
    description = "Builds the Rust core for ${rustAbis.joinToString()} with cargo-ndk."
    sources.from(rustSources())
    rustDir.set(rustRoot)
    abis.set(rustAbis)
    minSdk.set(libs.versions.minSdk.get().toInt())
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val cargoBuildHost = tasks.register<CargoHostBuild>("cargoBuildHost") {
    description = "Builds the Rust core for this machine (the JVM tests load it)."
    sources.from(rustSources())
    rustDir.set(rustRoot)
    library.set(rustRoot.file("target/release/$hostLibName"))
}

val uniffiBindings = tasks.register<UniffiBindings>("uniffiBindings") {
    description = "Generates the Kotlin bindings of the Rust core."
    library.set(cargoBuildHost.flatMap { it.library })
    rustDir.set(rustRoot)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindings::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoNdkBuild::outputDir)
    }
}

// The JVM unit tests call the real core through JNA, from the host build.
val hostLibDir: String = rustRoot.dir("target/release").asFile.absolutePath
tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", hostLibDir)
}

dependencies {
    // The generated bindings call the library through JNA. The AAR carries
    // JNA's Android natives; the plain jar carries the desktop ones the JVM
    // unit tests need.
    implementation(variantOf(libs.jna) { artifactType("aar") })
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    // On a device or emulator: the same calls through JNA's Android natives
    // and libsyn_android.so for the device's ABI (CI, emulator job).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
