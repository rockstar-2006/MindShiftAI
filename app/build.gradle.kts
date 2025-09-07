plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.aifreind"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aifreind"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ✅ Inject GEMINI_API_KEY
        val geminiKey: String = if (project.hasProperty("GEMINI_API_KEY")) {
            project.property("GEMINI_API_KEY") as String
        } else {
            ""
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

        // ✅ Inject MURF_API_KEY
        val murfKey: String = if (project.hasProperty("MURF_API_KEY")) {
            project.property("MURF_API_KEY") as String
        } else {
            ""
        }
        buildConfigField("String", "MURF_API_KEY", "\"$murfKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // ✅ This enables BuildConfig.java generation (needed for your API key)
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // ✅ Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // ✅ Firebase
    implementation("com.google.firebase:firebase-ml-modeldownloader:24.1.0")
    implementation ("com.squareup.okhttp3:okhttp:4.11.0")
    // ✅ TensorFlow Lite (cleaned up to avoid conflicts)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4")

    // ✅ Networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // ✅ Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
