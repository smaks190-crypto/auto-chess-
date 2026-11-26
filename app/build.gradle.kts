import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val asrAssets = layout.buildDirectory.dir("generated/noxAsrAssets")
val asrZip = asrAssets.map { it.file("vosk-ru-0.22.zip") }

val prepareRussianAsr by tasks.registering {
    outputs.file(asrZip)
    doLast {
        val out = asrZip.get().asFile
        out.parentFile.mkdirs()
        val temp = File(out.parentFile, "vosk-ru-0.22.part")
        val expected = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
        try {
            val connection = URI("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip").toURL().openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == expected) { "Vosk Russian model checksum mismatch" }
            check(temp.renameTo(out)) { "Could not prepare bundled speech model" }
        } finally { temp.delete() }
    }
}

android {
    sourceSets.getByName("main").assets.srcDir(asrAssets)
    namespace = "dev.nox.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.nox.local"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "0.4.10-ui-safety-dev"
        manifestPlaceholders["noxAppLabel"] = "N.O.X."
        manifestPlaceholders["noxNavigationLabel"] = "Нокс — навигация"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-parallel"
            manifestPlaceholders["noxAppLabel"] = "N.O.X. Dev"
            manifestPlaceholders["noxNavigationLabel"] = "Нокс Dev — навигация"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild") { dependsOn(prepareRussianAsr) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
