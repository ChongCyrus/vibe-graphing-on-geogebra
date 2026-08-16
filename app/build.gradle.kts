plugins {
    id("com.android.application")
}

android {
    namespace = "com.ggb.classic5"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ggb.classic5"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    // Intentionally empty: the app uses framework WebView + android.app APIs only.
}
