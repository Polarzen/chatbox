plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.polarzen.chatbox"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.polarzen.chatbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.1.17"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
