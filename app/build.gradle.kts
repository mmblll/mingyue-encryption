plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
android {
    namespace = "com.mingyue.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mingyue.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 8
        versionName = "1.4.2"
    }
    buildFeatures { compose = true }
    signingConfigs {
        val signingValues = listOf("MINGYUE_SIGNING_STORE", "MINGYUE_SIGNING_PASSWORD", "MINGYUE_SIGNING_ALIAS").map { System.getenv(it) }
        require(signingValues.all { it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) {
            "发布签名请同时设置 MINGYUE_SIGNING_STORE、MINGYUE_SIGNING_PASSWORD 和 MINGYUE_SIGNING_ALIAS"
        }
        if (signingValues.all { !it.isNullOrBlank() }) create("owner") {
            storeFile = file(System.getenv("MINGYUE_SIGNING_STORE"))
            storePassword = System.getenv("MINGYUE_SIGNING_PASSWORD")
            keyPassword = System.getenv("MINGYUE_SIGNING_PASSWORD")
            keyAlias = System.getenv("MINGYUE_SIGNING_ALIAS")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        getByName("release") { isDebuggable = false; signingConfig = signingConfigs.findByName("owner") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3:1.3.2")
}
