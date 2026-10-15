import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Google Maps key is kept out of version control: put `MAPS_API_KEY=...` in local.properties
// (or pass -PMAPS_API_KEY=...). Without it the app still runs, but map tiles stay blank.
val mapsApiKey: String = Properties()
    .apply {
        val localProperties = rootProject.file("local.properties")
        if (localProperties.exists()) localProperties.inputStream().use(::load)
    }
    .getProperty("MAPS_API_KEY")
    ?: providers.gradleProperty("MAPS_API_KEY").orNull
    ?: ""

if (mapsApiKey.isBlank()) {
    logger.warn("MAPS_API_KEY is not set in local.properties: the map will render without tiles.")
}

android {
    namespace = "com.example.frame_trip"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.frame_trip"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
