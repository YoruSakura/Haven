plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "sh.haven.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "sh.haven.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 949
        versionName = "5.94.24"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += listOf("abi", "features")
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("x64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
        create("armv7") { dimension = "abi"; ndk { abiFilters += "armeabi-v7a" } }
        create("full") { dimension = "features"; buildConfigField("boolean", "DESKTOP", "true") }
    }
    sourceSets.getByName("main").jniLibs.srcDir("${rootProject.projectDir}/rclone-android/jniLibs")
    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("haven-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        release {
            if (rootProject.file("haven-release.jks").exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
        }
        debug {
            if (System.getenv("KEYSTORE_PASSWORD") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("/META-INF/versions/*/OSGI-INF/MANIFEST.MF", "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/NOTICE.md", "/META-INF/LICENSE.md", "/META-INF/DEPENDENCIES", "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}")
        jniLibs.useLegacyPackaging = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
androidComponents {
    onVariants { variant ->
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
        val offset = mapOf("arm64" to 1, "x64" to 2, "armv7" to 3)[abi]
        variant.outputs.forEach { output ->
            if (offset != null) output.versionCode.set(949 * 10 + offset)
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set("haven-5.94.24-$abi-${variant.buildType}.apk")
        }
    }
}
dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:smb"))
    implementation(project(":core:fido"))
    implementation(project(":core:usb"))
    implementation(project(":core:platform"))
    implementation(project(":core:terminal-haven"))
    implementation(project(":core:tunnel"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))
    implementation(project(":feature:tunnel"))

    implementation(libs.termlib)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.process)
    implementation(libs.biometric)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20260814")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(gradle.includedBuild("rclone-android").task(":buildRcloneNative")) }
