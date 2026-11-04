plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.veluvara.pimonitor"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.veluvara.pimonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    signingConfigs {
        create("stable") {
            storeFile = file(System.getenv("SIGNING_STORE") ?: "../private-release.jks")
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "pi-monitor"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("stable"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
