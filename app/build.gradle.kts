import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// A Car App Library app only reaches a real car when it is installed from Play,
// and Play refuses a debug-signed artifact, so release builds need a real key.
// The key itself lives outside the project: without keystore.properties no
// release config is created at all, which keeps a fresh clone building.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.zuynew.amvideo.auto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zuynew.amvideo.auto"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // The same screens run on two different hosts, and each needs its own host
    // library: Android Auto projects from the phone, while Android Automotive OS
    // runs in the car's own head unit and needs a CarAppActivity to be launchable.
    // Shipping both libraries in one APK is not supported, hence the split.
    flavorDimensions += "host"
    productFlavors {
        create("mobile") { dimension = "host" }
        create("automotive") {
            dimension = "host"
            // app-automotive requires 29, and no Automotive OS head unit ships
            // below it. The phone build keeps the lower floor.
            minSdk = 29
        }
    }

    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // A handful of classes installed by hand: shrinking would save
            // nothing worth the extra moving part.
            isMinifyEnabled = false
            // Null when the key is absent, which leaves the artifact unsigned
            // rather than silently falling back to the debug key — an APK Play
            // would reject on upload instead of in the car.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // Phone UI
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Car UI
    implementation(libs.androidx.car.app)
    "mobileImplementation"(libs.androidx.car.app.projected)
    "automotiveImplementation"(libs.androidx.car.app.automotive)

    // Networking and storage
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
