plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val generatedMoshAssets = layout.projectDirectory.dir("src/main/assets")
val moshTerminfoSource = layout.projectDirectory.file("src/main/terminfo/xterm-256color.src")

val compileMoshTerminfo by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile the pinned xterm-256color entry used by native mosh-client"
    inputs.file(moshTerminfoSource)
    outputs.file(generatedMoshAssets.file("mosh/terminfo/x/xterm-256color"))

    doFirst {
        generatedMoshAssets.dir("mosh/terminfo").asFile.mkdirs()
    }
    commandLine(
        "tic",
        "-x",
        "-o",
        generatedMoshAssets.dir("mosh/terminfo").asFile.absolutePath,
        moshTerminfoSource.asFile.absolutePath,
    )
}

android {
    namespace = "sh.haven.core.mosh"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val moshAbiNames = mapOf(
    "arm64" to "arm64-v8a",
    "x64" to "x86_64",
    "armv7" to "armeabi-v7a",
)
val moshTargetAbi = providers.gradleProperty("targetAbi").orNull
val moshBuildAbis = moshTargetAbi?.let { listOfNotNull(moshAbiNames[it]) }
    ?: moshAbiNames.values.toList()
val moshNativeScript = rootProject.file("scripts/build-mosh-native.sh")

val buildMoshNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build upstream mosh-client 1.4.0 for Haven's Android ABIs"
    inputs.file(moshNativeScript)
    inputs.file(rootProject.file("scripts/native/mosh-ncurses-fallback.c"))
    inputs.property("abis", moshBuildAbis)
    outputs.dirs(moshBuildAbis.map { file("src/main/jniLibs/$it") })
    workingDir = rootProject.projectDir
    commandLine("bash", moshNativeScript.absolutePath, *moshBuildAbis.toTypedArray())

    onlyIf {
        val skipped = providers.gradleProperty("skipMoshNatives").orNull == "true"
        if (skipped) logger.lifecycle("[mosh] -PskipMoshNatives — not building")
        !skipped && moshBuildAbis.isNotEmpty()
    }
}

tasks.named("preBuild") {
    dependsOn(compileMoshTerminfo, buildMoshNative)
}

dependencies {
    api("sh.haven:ssp-transport:0.1.0")
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
