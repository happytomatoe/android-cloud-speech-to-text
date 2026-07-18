plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Git-based versioning: main gets exact tag, dev branches get -SNAPSHOT-<sha>.
// Resilient to missing git metadata (shallow clone, dubious ownership) so the
// build never fails purely because version probing could not run.
// The CI release workflow overrides this with -PVERSION_NAME (snapshot + unique id).
fun gitOutputOr(fallback: String, vararg args: String): String =
    runCatching {
        providers.exec { commandLine("git", *args) }
            .standardOutput.asText.get().trim()
    }.getOrDefault(fallback)

val gitVersionName: String = run {
    if (project.hasProperty("VERSION_NAME")) {
        project.property("VERSION_NAME") as String
    } else {
        val tag = gitOutputOr("0.0.0", "describe", "--tags", "--abbrev=0")
        val branch = gitOutputOr("dev", "rev-parse", "--abbrev-ref", "HEAD")
        val sha = gitOutputOr("dirty", "rev-parse", "--short", "HEAD")
        if (branch == "main") tag else "$tag-SNAPSHOT-$sha"
    }
}

val gitVersionCode: Int = run {
    // Use commit count as versionCode (monotonically increasing)
    gitOutputOr("1", "rev-list", "--count", "HEAD").toIntOrNull() ?: 1
}

android {
    namespace = "com.example.whispertoinput"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.whispertoinput"
        minSdk = 24
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("boolean", "DEBUG", "true")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        // Allow Robolectric to resolve R.layout.*, R.drawable.*, R.string.* from merged resources
        unitTests.isIncludeAndroidResources = true
        // Return default values for unmocked Android calls outside Robolectric's scope
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("io.ktor:ktor-client-okhttp:2.3.6")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    testImplementation("junit:junit:4.13.2")
    // Provides ApplicationProvider (Robolectric test context) for unit tests.
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Test infrastructure (Tiers 1-3): Robolectric runs Android framework code on the JVM
    // (no emulator); MockWebServer stubs the transcription HTTP endpoint; kotlinx-coroutines-test
    // gives deterministic scheduling for BackspaceButton's delay()-based long-press.
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Optional: mock final framework classes (e.g. MediaRecorder) in RecorderManagerTest
    testImplementation("io.mockk:mockk:1.13.11")
}

tasks.withType<Test> {
    // Run test classes concurrently across forks to cut total test time.
    // Pair with `./gradlew test --parallel` for cross-module parallelism.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Fork a fresh JVM per test class. Robolectric leaks Android/looper/coroutine
    // state across test classes that share a fork, which deadlocks
    // buildActivity()/DataStore-driven setup on later classes (e.g.
    // MainActivitySettingsTest hangs after ExampleUnitTest in a reused JVM).
    // A fresh JVM per class keeps each class hermetic and the suite reliable.
    forkEvery = 1
}