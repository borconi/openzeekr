import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Baked secrets now live in :core (SecretsConfig moved here). Same gitignored
// secrets.properties at the repo root; missing file -> all values empty. The
// generated BuildConfig is com.openzeekr.core.BuildConfig.
val secretsProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun bakedSecret(key: String): String =
    (secretsProps.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.openzeekr.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        // ONLY the six/seven app-global secrets are baked (never the account).
        buildConfigField("String", "SEC_HMAC_ACCESS_KEY", "\"${bakedSecret("HMAC_ACCESS_KEY")}\"")
        buildConfigField("String", "SEC_HMAC_SECRET_KEY", "\"${bakedSecret("HMAC_SECRET_KEY")}\"")
        buildConfigField("String", "SEC_PASSWORD_PUBLIC_KEY", "\"${bakedSecret("PASSWORD_PUBLIC_KEY")}\"")
        buildConfigField("String", "SEC_PROD_SECRET", "\"${bakedSecret("PROD_SECRET")}\"")
        buildConfigField("String", "SEC_VIN_KEY", "\"${bakedSecret("VIN_KEY")}\"")
        buildConfigField("String", "SEC_VIN_IV", "\"${bakedSecret("VIN_IV")}\"")
        buildConfigField("String", "SEC_XCHANGER_SIGN_SECRET", "\"${bakedSecret("XCHANGER_SIGN_SECRET")}\"")
        // Overseas-app (Azure gateway) HMAC AK/SK — for the message inbox. Native
        // (getNativeApplicationId / getNativeSecret in libenv.so), Frida-dumped per region/env.
        buildConfigField("String", "SEC_OVERSEAS_ACCESS_KEY", "\"${bakedSecret("OVERSEAS_ACCESS_KEY")}\"")
        buildConfigField("String", "SEC_OVERSEAS_SECRET_KEY", "\"${bakedSecret("OVERSEAS_SECRET_KEY")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

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

    // DK BLE crypto
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Offline crypto unit tests (RPA CMAC / ECIES key unwrap)
    testImplementation("junit:junit:4.13.2")
}
