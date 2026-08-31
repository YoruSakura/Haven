plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.spice"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Include native libraries built from the pinned spice-kotlin Rust source.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${rootProject.projectDir}/spice-kotlin/jniLibs")
        }
    }
}

// Keep the submodule pristine while Haven owns the shipped ABI policy. The
// upstream included build still knows about desktop/emulator x86; invoking
// cargo-ndk here guarantees release builds produce only the two Android ARM
// libraries and lets -PtargetAbi avoid rebuilding the other split.
val spiceAbiNames = mapOf(
    "arm64" to "arm64-v8a",
    "armv7" to "armeabi-v7a",
)
val spiceTargetAbi = providers.gradleProperty("targetAbi").orNull
val spiceBuildAbis = spiceTargetAbi?.let { listOfNotNull(spiceAbiNames[it]) }
    ?: spiceAbiNames.values.toList()

val buildSpiceNative by tasks.registering(Exec::class) {
    val rustDir = rootProject.file("spice-kotlin/rust")
    val jniDir = rootProject.file("spice-kotlin/jniLibs")

    inputs.dir(File(rustDir, "src"))
    inputs.dir(File(rustDir, "vendor"))
    inputs.file(File(rustDir, "Cargo.toml"))
    inputs.file(File(rustDir, "Cargo.lock"))
    inputs.property("abis", spiceBuildAbis)
    outputs.dirs(spiceBuildAbis.map { File(jniDir, it) })

    workingDir = rustDir
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: (System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME"))?.let { sdk ->
            File(sdk, "ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) environment("ANDROID_NDK_HOME", ndkHome)
    // Bypass the submodule's rust-toolchain target list (which also names
    // x86_64) while retaining its pinned compiler version.
    environment("RUSTUP_TOOLCHAIN", "1.89.0")

    val targetArgs = spiceBuildAbis.flatMap { listOf("-t", it) }
    commandLine(listOf("cargo", "ndk", "-o", jniDir.absolutePath) + targetArgs + listOf("build", "--release"))

    onlyIf {
        val present = File(rustDir, "Cargo.toml").isFile
        if (!present) logger.lifecycle("[spice] submodule not checked out — skipping native build")
        present && spiceBuildAbis.isNotEmpty()
    }
}

tasks.named("preBuild") { dependsOn(buildSpiceNative) }

dependencies {
    api("sh.haven:spice-transport:0.1.0")
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
