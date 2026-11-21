import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("**/DesktopMain.kt", "**/Sse.kt", "**/AstrBot.kt")
    }
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
compose.desktop {
    application {
        mainClass = "dev.polarzen.chatbox.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Chatbox"
            packageVersion = "0.1.0"
            modules("java.prefs", "jdk.crypto.ec")
            windows { upgradeUuid = "204b3f78-64ec-4071-9a66-a535773540eb" }
        }
    }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
val desktopSmoke by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.polarzen.chatbox.DesktopMainKt")
    args("--smoke-test")
}
