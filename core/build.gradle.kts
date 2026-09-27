import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Baked secrets now live in :core (SecretsConfig moved here). Same gitignored
// secrets.properties at the repo root; missing file -> all values empty. The
// generated BuildConfig is com.openzeekr.core.BuildConfig.
val secretsProps =
    Properties().apply {
        val f = rootProject.file("secrets.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun bakedSecret(key: String): String = (secretsProps.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

// ---- JNI native secrets: inject the genuinely-secret VALUES into a gitignored C header at
//      build time, so they live in libozsecrets.so instead of a BuildConfig DEX string.
// The committed C (src/main/cpp/ozsecrets.c) #includes this generated header; the header is
// regenerated on every configure and is gitignored, so NO real secret is ever committed. Blank
// when secrets.properties is absent (clean repo) -> app starts blank, configured in Settings.
// NOTE: the RSA password_public_key is a PUBLIC key, so it stays in BuildConfig (see below) -
//       nothing is gained by hiding a public key, matching the "leave public certs" rule.
val nativeSecretKeys =
    listOf(
        "HMAC_ACCESS_KEY",
        "HMAC_SECRET_KEY",
        "PROD_SECRET",
        "XCHANGER_SIGN_SECRET",
        "OVERSEAS_ACCESS_KEY",
        "OVERSEAS_SECRET_KEY",
        "INBOX_AUTH_SECRET",
        "VIN_KEY",
        "VIN_IV",
        // Per-region signing sets (only the 3 signing secrets differ by region; everything else is
        // shared). The bare keys above are the EU/default set. SEA is extracted; EM (LA/ME) is wired
        // but blank until extracted (`--region EM`), so LA/ME fall back to the EU set for now.
        "SEA_HMAC_ACCESS_KEY",
        "SEA_HMAC_SECRET_KEY",
        "SEA_PROD_SECRET",
        "EM_HMAC_ACCESS_KEY",
        "EM_HMAC_SECRET_KEY",
        "EM_PROD_SECRET",
    )
run {
    fun cEscape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    val header = file("src/main/cpp/secrets_generated.h")
    header.parentFile.mkdirs()
    val text =
        buildString {
            appendLine("// AUTO-GENERATED at build time from secrets.properties.")
            appendLine("// DO NOT COMMIT and DO NOT EDIT - this file is gitignored and holds real values.")
            appendLine("#ifndef OZ_SECRETS_GENERATED_H")
            appendLine("#define OZ_SECRETS_GENERATED_H")
            nativeSecretKeys.forEach { k ->
                appendLine("#define OZ_SEC_$k \"${cEscape(secretsProps.getProperty(k) ?: "")}\"")
            }
            appendLine("#endif")
        }
    // Only rewrite when changed, so we don't needlessly invalidate the native build cache.
    if (!header.exists() || header.readText() != text) header.writeText(text)
}

android {
    namespace = "com.openzeekr.core"
    compileSdk = 34

    // Build the native secrets lib (libozsecrets.so). The NDK version is pinned once, in
    // gradle.properties (openzeekr.ndkVersion), and must be installed under the SDK
    // (Android Studio > SDK Manager > NDK). An unavailable NDK version fails the native build.
    ndkVersion = providers.gradleProperty("openzeekr.ndkVersion").get()

    defaultConfig {
        minSdk = 26

        // The genuinely-secret app-global values now live in the JNI native lib (libozsecrets.so),
        // injected at build time from secrets.properties (see the header generation above and
        // com.openzeekr.app.util.NativeSecrets). They are intentionally NOT BuildConfig strings.
        // Only the RSA password_public_key stays in BuildConfig - it is a PUBLIC key, so hiding it
        // in native would add no security (matches the "public certs/keys stay put" rule).
        buildConfigField("String", "SEC_PASSWORD_PUBLIC_KEY", "\"${bakedSecret("PASSWORD_PUBLIC_KEY")}\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    lint {
        // Same policy in :app, :core and :wear. See "Lint policy" in the root build.gradle.kts.
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        disable += "GradleDependency"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Exposed to :app and :wear (they use these types directly), hence `api`.
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (cloud TSP)
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Encrypted config storage
    api("androidx.security:security-crypto:1.1.0-alpha06")

    // Activity Recognition (proximity motion fallback when there's no hardware motion sensor)
    api("com.google.android.gms:play-services-location:21.3.0")

    // FCM push (car message-centre alarms while the screen is off). NO google-services plugin /
    // google-services.json — the default FirebaseApp auto-inits from the stock project's string
    // resources (core/src/main/res/values/secrets_firebase.xml) via FirebaseInitProvider, which
    // firebase-messaging pulls in transitively (firebase-common). `api` so :app inherits it.
    api(platform("com.google.firebase:firebase-bom:33.5.1"))
    api("com.google.firebase:firebase-messaging")

    // DK BLE crypto
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Offline crypto unit tests (RPA CMAC / ECIES key unwrap)
    testImplementation("junit:junit:4.13.2")
}
