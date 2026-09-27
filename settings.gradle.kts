pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenZeekr"
include(":app")
include(":core")
include(":wear")

// ---- Environment preflight ------------------------------------------------------------------
// Catches the usual first-build setup problems up front, with the fix, instead of a deep AGP
// error later ("jlink executable ... does not exist", "SDK location not found"). Setup guide:
// docs/DEVELOPMENT.md.
run {
    val guide = "Setup guide: docs/DEVELOPMENT.md"
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val javaHome = File(System.getProperty("java.home"))
    val errors = mutableListOf<String>()

    if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
        errors += "Gradle is running on Java ${JavaVersion.current()} ($javaHome); JDK 17 or newer is required."
    }
    if (!File(javaHome, if (isWindows) "bin/jlink.exe" else "bin/jlink").exists()) {
        errors += "The JDK at $javaHome has no jlink (a JRE or \"headless\" package). " +
            "Install a full JDK 17 (e.g. Temurin) and point JAVA_HOME at it."
    }

    // Same lookup order as AGP: local.properties sdk.dir, then ANDROID_HOME, then ANDROID_SDK_ROOT.
    val localProps = java.util.Properties()
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
    val sdkDir =
        listOf(localProps.getProperty("sdk.dir"), System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .firstOrNull { !it.isNullOrBlank() }
            ?.let(::File)
    if (sdkDir == null || !sdkDir.isDirectory) {
        errors += "Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME (currently: ${sdkDir ?: "unset"})."
    } else {
        // Only a warning: AGP can download a missing NDK itself when the SDK licenses are accepted.
        val ndk = providers.gradleProperty("openzeekr.ndkVersion").get()
        if (!File(sdkDir, "ndk/$ndk").isDirectory) {
            logger.warn("OpenZeekr: NDK $ndk is not installed. Install it with: sdkmanager --install \"ndk;$ndk\". $guide")
        }
    }

    if (errors.isNotEmpty()) {
        throw GradleException(
            "OpenZeekr build environment is not ready:\n" + errors.joinToString("\n") { "  - $it" } + "\n$guide",
        )
    }
}
