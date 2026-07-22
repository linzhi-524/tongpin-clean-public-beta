plugins {
    id("com.android.application")
}

android {
    namespace = "com.linjian.tongpin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linjian.tongpin"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.3.1-public"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
