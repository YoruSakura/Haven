plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

// Build FFmpeg (and its 12 codec/font dependencies) from pinned source and
// place the three binaries in jniLibs, instead of shipping committed copies
// (#493). F-Droid already did this — their recipe `scandelete`s
// core/ffmpeg/src/main/jniLibs and runs build-ffmpeg/build.sh — so it was
// *our* release workflow that was the odd one out, shipping 172 MB of blobs
// nothing rebuilt.
//
// Cost, measured rather than assumed: a clean build of the whole chain
// (x264, x265, vpx, lame, opus, ogg, vorbis, freetype, fribidi, harfbuzz,
// libass, mbedtls, then FFmpeg n8.0) is 2m42s on a 12-core desktop. The
// issue estimated ~1h per ABI; that was wrong by about twentyfold, and the
// estimate is what had kept these committed.
//
// One ABI per invocation. Each release matrix job builds only the ABI it
// packages, so the wall-clock cost is one build per job, in parallel — not
// both.
val ffmpegAbis = mapOf(
    "arm64" to "arm64-v8a",
    "armv7" to "armeabi-v7a",
)
val ffmpegScript = rootProject.file("build-ffmpeg/build.sh")
val pinnedNdk = "29.0.14206865"
val ndkDir: String = run {
    val sdk = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
        ?: File(System.getProperty("user.home"), "Android/Sdk").absolutePath
    File(sdk, "ndk/$pinnedNdk").absolutePath
}

val buildFfmpegNatives by tasks.registering {
    val targetAbi = providers.gradleProperty("targetAbi").orNull
    // No -PtargetAbi (a plain local build) means every ABI, because there is
    // no flavour to infer one from and shipping an APK missing ffmpeg is worse
    // than a slow build.
    val abis = targetAbi?.let { listOfNotNull(ffmpegAbis[it]) } ?: ffmpegAbis.values.toList()

    // build.sh carries every source pin — 9 tarball sha256s and 4 commit SHAs —
    // so hashing it captures everything that decides the output.
    inputs.file(ffmpegScript)
    inputs.property("abis", abis)
    inputs.property("ndk", pinnedNdk)
    outputs.dirs(abis.map { file("src/main/jniLibs/$it") })

    onlyIf {
        val skipped = providers.gradleProperty("skipFfmpegNatives").orNull == "true"
        if (skipped) logger.lifecycle("[ffmpeg] -PskipFfmpegNatives — not building")
        !skipped && abis.isNotEmpty() && ffmpegScript.exists()
    }

    doLast {
        require(File(ndkDir).isDirectory) {
            "NDK $pinnedNdk not found at $ndkDir — sdkmanager \"ndk;$pinnedNdk\""
        }
        abis.forEach { abi ->
            logger.lifecycle("[ffmpeg] building $abi (a few minutes)")
            val proc = ProcessBuilder("bash", ffmpegScript.absolutePath)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["ABI"] = abi
                    // Same reason as core/wayland: the runner image presets
                    // ANDROID_NDK_HOME to its own older NDK, and the script
                    // prefers the environment over the newest available.
                    environment()["ANDROID_NDK_HOME"] = ndkDir
                }
                .start()
            val tail = ArrayDeque<String>()
            proc.inputStream.bufferedReader().forEachLine {
                logger.info(it)
                tail.addLast(it)
                if (tail.size > 40) tail.removeFirst()
            }
            val code = proc.waitFor()
            if (code != 0) {
                logger.error("--- build.sh ($abi) last ${tail.size} lines ---")
                tail.forEach { logger.error(it) }
            }
            require(code == 0) { "build-ffmpeg/build.sh failed for $abi with exit code $code" }

            val out = file("src/main/jniLibs/$abi")
            // Everything the script leaves in bin/: the two executables, the
            // shared libav*/libsw* libraries they link against, and
            // libc++_shared.so. Copied by pattern rather than by name so a
            // library FFmpeg adds or renames travels with the build instead of
            // being silently dropped and failing at exec time on the device.
            out.deleteRecursively()
            copy {
                from(rootProject.file("build-ffmpeg/build-$abi/install/bin")) {
                    include("lib*.so")
                }
                into(out)
            }
            val produced = out.listFiles()?.map { it.name }?.sorted().orEmpty()
            val expected = listOf("libffmpeg.so", "libffprobe.so", "libc++_shared.so")
            val missing = expected.filterNot { it in produced }
            require(missing.isEmpty()) { "ffmpeg build produced no $missing in $out" }
            // The executables are dynamically linked against these now, so an
            // empty set means the shared build silently reverted to static and
            // the APK would be ~11 MB larger for no reason.
            require(produced.any { it.startsWith("libav") }) {
                "ffmpeg build produced no shared libav* libraries in $out (got $produced)"
            }
            logger.lifecycle("[ffmpeg] $abi: ${produced.size} binaries into $out — $produced")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildFfmpegNatives)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
