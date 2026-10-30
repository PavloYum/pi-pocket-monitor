plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.veluvara.pimonitor"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.veluvara.pimonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
