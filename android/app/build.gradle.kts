plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.whispertoinput"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.whispertoinput"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "0.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreB64 = System.getenv("KEYSTORE_BASE64")
            if (keystoreB64 != null) {
                storeFile = file("/tmp/release-key.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreB64 = System.getenv("KEYSTORE_BASE64")
            if (keystoreB64 != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            buildConfigField("boolean", "DEBUG", "true")
        }
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
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
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("com.github.liuyueyi:quick-transfer-core:0.2.13")
}