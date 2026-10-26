import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.finbyte.mispay_android_sdk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.finbyte.mispay_android_sdk"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Demo credentials live in app/credentials.properties (gitignored).
        // Missing file -> REPLACE_ME placeholders, and the demo surfaces ME03.
        val mispayCreds = Properties().apply {
            val f = file("credentials.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "MISPAY_APP_ID",
            "\"${mispayCreds.getProperty("MISPAY_APP_ID") ?: "REPLACE_ME"}\""
        )
        buildConfigField(
            "String",
            "MISPAY_APP_SECRET",
            "\"${mispayCreds.getProperty("MISPAY_APP_SECRET") ?: "REPLACE_ME"}\""
        )
    }

    buildFeatures {
        buildConfig = true
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":mispaysdk"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}