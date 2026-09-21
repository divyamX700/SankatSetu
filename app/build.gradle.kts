import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Read from the machine-local, gitignored local.properties — same file the
// SDK path already lives in — never from a committed file. Missing key
// (a fresh clone with no key set up yet) falls back to an empty string
// rather than failing the build; MapScreen's own tile source construction
// is what actually needs to handle "no key configured" honestly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapTilerApiKey: String = localProperties.getProperty("maptiler.api.key", "")

android {
    namespace = "com.sankatsetu.app"
    // compileSdk/targetSdk 34, not 35: build-tools 35.0.0's aapt2 fails to
    // even parse platform 35's android.jar under AGP 7.4.2
    // ("RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data") — a
    // real aapt2/AGP-era incompatibility, not just a version-support flag.
    // 34 is well inside AGP 7.4.2's tested range. See docs/adr/0006.
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.sankatsetu.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-hackathon"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // See docs/adr/0020-offline-maps.md — the public OSM tile server
        // (osmdroid's TileSourceFactory.MAPNIK) explicitly refuses bulk/
        // offline downloads in code (TileSourcePolicyException,
        // FLAG_NO_BULK), a real ToS-driven guardrail, not a bug. MapTiler's
        // free tier (no card, ~100k loads/month) permits it.
        buildConfigField("String", "MAPTILER_API_KEY", "\"$mapTilerApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // flip on once we've verified Cedar/MediaPipe JNI survive R8
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            // Demo-mode flag: disables the panic-wipe triple-tap so judges
            // fumbling the demo phone don't nuke it (see docs/adr/0004).
            buildConfigField("boolean", "DEMO_MODE_DEFAULT", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 1.9.24's Compose compiler is a separate artifact selected here
    // (pre-K2 model) rather than via the org.jetbrains.kotlin.plugin.compose
    // Gradle plugin (Kotlin 2.0+ only) — see docs/adr/0006.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // AGP 7.4.2 calls this block `packagingOptions`, not `packaging`
    // (renamed in AGP 8.0) — see docs/adr/0006-jdk11-toolchain-downgrade.md.
    packagingOptions {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    // --- Compose ---
    // Pinned to the BOM generation that pairs with Compose Compiler 1.5.14
    // (the last one compatible with Kotlin 1.9.24 pre-K2) — see docs/adr/0006.
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Real drawn icons for the bottom nav (Chat/Pay/Assistant) and other
    // chrome — replaces emoji-as-icons, which a design review flagged as
    // reading as AI-generated placeholder art rather than a real icon
    // system. See docs/adr/0013-operate-mode-color-and-icons.md.
    implementation("androidx.compose.material:material-icons-extended")
    // activity-compose/lifecycle pinned older than latest: AGP 7.4.2's bundled
    // D8 crashes with a bare NullPointerException (not a clean version error)
    // dexing androidx.lifecycle:lifecycle-livedata-core 2.8.7's class files —
    // see docs/adr/0006-jdk11-toolchain-downgrade.md.
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Persistence: Room + SQLCipher (matches Flowpay's data-layer pattern) ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // --- Crypto: Noise Protocol (XX/X patterns) + Ed25519 signing ---
    // rweather/noise-java — reference Java implementation of the Noise Protocol
    // Framework. Ported/wrapped in mesh/crypto/NoiseSession.kt. It is NOT
    // published to Maven Central under any coordinate (verified: no tags, no
    // releases, plain pom.xml with a typo'd groupId "com.southerstorm") — the
    // com.southernstorm:noise-java:0.1.0 coordinate this project started with
    // does not exist. Consumed via JitPack instead, which builds straight
    // from the GitHub repo; "master-SNAPSHOT" tracks the repo's only branch
    // since it has no tags. See docs/adr/0006-jdk11-toolchain-downgrade.md
    // and docs/adr/0002-vendoring-and-porting-strategy.md.
    implementation("com.github.rweather:noise-java:master-SNAPSHOT")

    // --- QR scan-to-pay (Pay tab) ---
    // Re-added after being removed as dead code in 08b5e67 ("a whole 'QR
    // setup handshake' dependency block... never actually used anywhere in
    // the app's code") — this time it IS used, by QrScanScreen.kt, wired
    // into a real PayScreen entry point. Only zxing-android-embedded, not
    // the CameraX artifacts the removed block also had: this integration
    // uses the library's own bundled ScanContract/CaptureActivity (its own
    // Camera1/Camera2 handling internally), never touching CameraX's API
    // directly, so there's nothing for a separate CameraX dependency to do
    // here. Simpler surface area to get right with no device available to
    // test a hand-rolled CameraX preview against. See
    // docs/adr/0025-qr-scan-to-pay.md.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // --- On-device LLM assistant (Day 2, F2) ---
    // The model file itself is never bundled — see
    // docs/adr/0005-model-assets-not-committed.md — this is just the
    // inference runtime. If this pulls in bytecode too new for AGP 7.4.2's
    // D8 (see docs/adr/0006's CameraX/Lifecycle precedent), pin to an older
    // tasks-genai release the same way.
    implementation("com.google.mediapipe:tasks-genai:0.10.20") // see docs/adr/0011

    // --- Cedar authorization (Day 3, AWS Build It) ---
    // NOT a plain `implementation("com.cedarpolicy:cedar-java:4.3.1")` —
    // that coordinate is real and correctly verified against Maven Central
    // (NOT 4.10.0, which an earlier draft plan hallucinated), but the
    // unmodified jar cannot be dexed on this project's toolchain: both
    // AGP 7.4.2's bundled D8 (R8 4.0.52) AND SDK 34's own bundled D8
    // (R8 8.2.2-dev) crash with a NullPointerException on
    // PolicySetSerializer.class — a real, documented upstream R8/D8 bug
    // (empty-name entries in the `MethodParameters` attribute, common on
    // compiler-generated bridge methods; fixed upstream only in R8
    // 8.0.44+/8.1.44+, a fix apparently not present in either D8 build
    // available on this machine). Confirmed independently by running the
    // SDK's own `d8` tool directly on the unmodified jar. See
    // docs/adr/0017-cedar-cross-compile.md for the full diagnosis.
    //
    // Fix: `app/libs/cedar-java-4.3.1-methodparams-stripped.jar` is the
    // real, unmodified 4.3.1 jar with only the MethodParameters attribute
    // stripped from every class (via a small ASM-based tool, not hand
    // edited) — that attribute only carries reflection-visible parameter
    // names, which this app never inspects via java.lang.reflect.Parameter,
    // so removing it is behaviorally invisible. Verified: the patched jar
    // dexes cleanly with the same `d8` invocation that crashed on the
    // original. Declaring it as a local file dependency means Gradle's own
    // POM-based transitive resolution doesn't run for it, so cedar-java's
    // real runtime dependencies (Jackson for JSON, com.fizzed:jne for
    // cedar-java's LibraryLoader — never actually exercised on Android,
    // see CedarAuthorizer.prepareNativeLibraryPath — and Guava) are
    // declared explicitly below instead.
    implementation(files("libs/cedar-java-4.3.1-methodparams-stripped.jar"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.2")
    implementation("com.fizzed:jne:4.3.0")
    implementation("com.google.guava:guava:33.4.0-jre")

    // --- Offline maps (2km-radius area download, Day 4) ---
    // Pure Kotlin/Java, no native .so — a deliberate choice given this
    // project's own history of native-dependency toolchain pain (Cedar,
    // docs/adr/0017). Archived upstream since Aug 2024 (v6.1.20 is final,
    // no more releases) but still functional; Mapsforge (actively
    // maintained, also pure Java, vector-based) is the documented upgrade
    // path if there's ever time — see docs/adr/0020 once written.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// error_prone_annotations independently crashes the same D8 bug as
// cedar-java did (see the Cedar dependency block above and
// docs/adr/0017-cedar-cross-compile.md) — confirmed by dexing it alone.
// A per-dependency `exclude` on Guava wasn't enough because more than one
// dependency in this graph pulls it in transitively; excluding it globally
// here is what actually keeps it out of every configuration's resolved
// classpath. It is compile-time-only annotation metadata
// (@Immutable, @CanIgnoreReturnValue, ...) that nothing in this app
// inspects via reflection, so dropping it entirely is safe.
configurations.all {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")

    // Real, on-device-confirmed bug: see the "Guava variant selection"
    // note in settings.gradle.kts (dependencyResolutionManagement) for the
    // full diagnosis and actual fix — Guava's "33.4.0-jre" coordinate
    // publishes Gradle Module Metadata with an android-flavored variant
    // that AGP's consumer attributes force-select regardless of the
    // "-jre" label, and neither a version `force` nor a `configurations.all { attributes {...} }`
    // override here was enough to stop it (AGP re-applies its own
    // attributes after this block runs). The fix that actually works lives
    // one level up, in the repository's `metadataSources`.
}

// Room's exportSchema defaults to true (correct — see docs/PRD.md §9.1's
// "never fallbackToDestructiveMigration" rule) but needs an explicit output
// directory or the KSP processor just warns and skips it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
