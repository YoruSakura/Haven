plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Keep version in sync with [versions.jna] in gradle/libs.versions.toml
    compileOnly("net.java.dev.jna:jna:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

// Generated Kotlin sources live under kotlin/ (see tools/build-android.sh)
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build IronRDP native library from Rust source via cargo-ndk.
// Prerequisites: rustup, cargo-ndk, and the two Android ARM targets below.
//
// The .so files under jniLibs/ are NOT committed — 4d096470 removed them and
// .gitignore keeps them out. Every shipped ABI is rebuilt from source by this
// task, which is what makes that safe: armeabi-v7a was once missing from the
// target list, so a Rust change rebuilt arm64 and x86_64 and left armv7 users
// on whatever binary happened to be checked in — the silent-staleness failure
// #469 was, in a different module. Adding an ABI to the APK means adding it
// here in the same change.
val buildRdpNative by tasks.registering(Exec::class) {
    val rustDir = file("rust")
    val jniDir = file("jniLibs")

    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    outputs.dir(jniDir)

    workingDir = rustDir

    // Detect NDK from ANDROID_NDK_HOME or ANDROID_SDK_ROOT
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }

    val abiNames = mapOf(
        "arm64" to "arm64-v8a",
        "armv7" to "armeabi-v7a",
    )
    val targetAbi = providers.gradleProperty("targetAbi").orNull
    val buildAbis = targetAbi?.let { listOfNotNull(abiNames[it]) } ?: abiNames.values.toList()
    inputs.property("abis", buildAbis)
    val targetArgs = buildAbis.flatMap { listOf("-t", it) }

    commandLine(listOf("cargo", "ndk", "-o", jniDir.absolutePath) + targetArgs + listOf("build", "--release"))
}

// No publishing block needed — consumed via includeBuild() in settings.gradle.kts
