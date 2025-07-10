plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.csvmodifier"
    compileSdk = 35


    defaultConfig {
        applicationId = "com.example.csvmodifier"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        dataBinding = true
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)



    implementation("androidx.core:core-ktx:1.13.1") // Or latest
    implementation("androidx.appcompat:appcompat:1.6.1") // Or latest
    implementation("com.google.android.material:material:1.12.0") // Or latest
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Or latest

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0") // Or latest
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")// Or latest

    implementation("androidx.activity:activity-ktx:1.9.0") // For by viewModels()

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0") // Or latest
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0") // Or latest
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0") // Or a recent version

    // Kotlin CSV (https://github.com/doyaaaaaken/kotlin-csv)
    implementation ("com.github.doyaaaaaken:kotlin-csv-jvm:1.9.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.airbnb.android:lottie:6.5.0")

    implementation("com.google.android.material:material:1.12.0")

    // For making HTTP requests to Veeva API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}