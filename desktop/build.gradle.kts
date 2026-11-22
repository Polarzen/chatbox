import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

providers.gradleProperty("chatboxBuildDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
tasks.named<KotlinCompile>("compileKotlin") {
    source(fileTree("../app/src/main/java") {
        include("**/Sse.kt", "**/AstrBot.kt")
    })
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}
val windowsPackageVersion = "0.1.6"
compose.desktop {
    application {
        mainClass = "dev.polarzen.chatbox.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Chatbox"
            packageVersion = windowsPackageVersion
            modules("java.prefs", "jdk.crypto.ec")
            windows {
                upgradeUuid = "204b3f78-64ec-4071-9a66-a535773540eb"
                menu = true
            }
        }
    }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
val desktopSmoke by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.polarzen.chatbox.DesktopMainKt")
    args("--smoke-test")
}

val packagedMsi = layout.buildDirectory.file("compose/binaries/main/msi/Chatbox-${windowsPackageVersion}.msi")
tasks.configureEach {
    if (name == "packageMsi") {
        inputs.file(layout.projectDirectory.file("patch-windows-msi.ps1"))
        doLast {
            val msiFile = packagedMsi.get().asFile
            if (!msiFile.isFile) error("Expected packageMsi output at ${msiFile.absolutePath}")
            project.exec {
                commandLine(
                    "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File",
                    layout.projectDirectory.file("patch-windows-msi.ps1").asFile.absolutePath,
                    "-MsiPath", msiFile.absolutePath, "-ExpectedVersion", windowsPackageVersion
                )
            }
        }
    }
}
