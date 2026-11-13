pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

    }
}

// Vendored terminal sources include the scrollback and physical-keyboard fixes.
// See docs/TERMINAL_SOURCES.md for the upstream revision and local changes.
includeBuild("termlib") {
    dependencySubstitution {
        substitute(module("org.connectbot:termlib")).using(project(":lib"))
    }
}

// Pure Kotlin ET transport library (submodule).
includeBuild("et-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:et-transport")).using(project(":"))
    }
}

// Pure Kotlin SSP transport library (submodule).
includeBuild("mosh-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:ssp-transport")).using(project(":"))
    }
}

// IronRDP + UniFFI Kotlin bindings (submodule).
includeBuild("rdp-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:rdp-transport")).using(project(":"))
    }
}

// Shared gomobile runtime for the five retained userspace VPN protocols.
includeBuild("rclone-android") {
    dependencySubstitution {
        substitute(module("sh.haven:rclone-transport")).using(project(":"))
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:terminal-haven")
include(":core:toolbar")
include(":core:ssh")
include(":core:security")
include(":core:data")
include(":core:redact")
include(":core:tunnel")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:keys")
include(":feature:tunnel")
include(":core:mosh")
include(":core:et")
include(":core:vnc")
include(":core:rdp")
include(":core:smb")
include(":core:fido")
include(":core:usb")

include(":feature:settings")
include(":feature:editor")
include(":feature:vnc")
include(":feature:rdp")

include(":integration-tests")
include(":core:platform")
