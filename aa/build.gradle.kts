import java.io.FileInputStream
import java.util.Properties

// Wireless Android Auto manipulator, folded in from the standalone
// io.aawireless.wirelessaamanipulator app as a library module. It is a self-contained
// Java codebase (foreground service + BT/USB bootstrap + TCP bridge + protobuf AA
// messages + Waze police HUD) that :app drives from the Compose "Android Auto" tab.
//
// It lives in its own module (not folded straight into :app) for two reasons:
//  - it needs the protobuf Gradle plugin + generated lite sources, which we keep out
//    of the Compose app module;
//  - it carries its own package, raw cert/EV-profile resources and manifest components,
//    so isolating it avoids any resource/name clash with :app.
plugins {
    id("com.android.library")
    id("com.google.protobuf")
}

// Waze / OpenWeb-Ninja key: read from the gitignored local.properties (never committed),
// surfaced as BuildConfig.WAZE_API_KEY exactly as the original app expected. The in-app
// Settings field overrides it at runtime, so an empty value here still builds fine.
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply { if (localPropsFile.exists()) load(FileInputStream(localPropsFile)) }
val wazeApiKey: String = (localProps.getProperty("WAZE_API_KEY") ?: System.getenv("WAZE_API_KEY") ?: "")

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

android {
    namespace = "io.aawireless.wirelessaamanipulator"
    compileSdk = 35

    defaultConfig {
        // Kept at the app's minSdk (26) so it never forces the app's floor up. The AA
        // feature itself needs Android 13+ at runtime (wireless AA / FGS connectedDevice /
        // getParcelableExtra(Class)); AndroidAutoScreen gates the controls below API 33.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "WAZE_API_KEY", "\"$wazeApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The ported Java was written against minSdk 33; built here at minSdk 26 it trips
        // NewApi on the 13+ calls that AndroidAutoScreen already gates at runtime. Don't let
        // that fail the build - this module is outside the :app/:core/:wear verifyLint gate.
        abortOnError = false
        disable += "NewApi"
    }
}

dependencies {
    // AA protobuf messages (lite runtime) generated from src/main/proto.
    implementation("com.google.protobuf:protobuf-javalite:4.33.5")
    // ContextCompat / NotificationCompat used by the service + receivers.
    implementation("androidx.core:core-ktx:1.13.1")
    // FusedLocationProviderClient (weather lookup off the last-known fix) in MainTaskService.
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
