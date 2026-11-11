plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.cosrai.dropship"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "com.cosrai.dropship.v05"; minSdk = 29; targetSdk = 35; versionCode = 8; versionName = "0.7.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

dependencies { implementation("com.google.zxing:core:3.5.3") }
