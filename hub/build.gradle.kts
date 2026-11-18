import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

// The APK for the TV must be signed by the release key of signing/release-cert.sha256 (see docs/SIGNING.md):
// Android refuses an update signed by another key. The debug key is for explicit test builds and CI only.
val signing = File(System.getenv("S4F_SIGNING") ?: "${System.getProperty("user.home")}/.config/streaming4free/signing/signing.properties")
val signingProps = Properties().apply { if (signing.isFile) signing.inputStream().use { load(it) } }
val testBuild = System.getenv("S4F_TEST_BUILD") == "1" || System.getenv("CI") == "true"

plugins {
    id("com.android.application") version "9.5.1" // Kotlin support is built in since AGP 9
}

android {
    namespace = "com.local.streaming4free"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.local.streaming4free"
        minSdk = 23
        targetSdk = 34 // same as WATCHED and CINEFUSE TV
        versionCode = 2
        versionName = "1.1"
    }
    if (signing.isFile) signingConfigs.create("stable") {
        storeFile = file(signingProps.getProperty("storeFile"))
        storePassword = signingProps.getProperty("storePassword")
        keyAlias = signingProps.getProperty("keyAlias")
        keyPassword = signingProps.getProperty("keyPassword")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // checkReleaseKey stops the build before packaging when this is the debug key outside a test build
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("io.coil-kt:coil:2.7.0")
    testImplementation("junit:junit:4.13.2")
}

val checkReleaseKey by tasks.registering {
    val props = signingProps
    val path = signing.path
    val test = testBuild
    val expected = rootDir.resolve("../signing/release-cert.sha256").readText().trim()
    doLast {
        if (props.isEmpty) {
            if (!test) throw GradleException("No release key ($path). See docs/SIGNING.md; for a test build only: S4F_TEST_BUILD=1.")
            logger.warn("Test build: signed with the debug key. It cannot update the apps installed on the TV.")
            return@doLast
        }
        val ks = KeyStore.getInstance(File(props.getProperty("storeFile")), props.getProperty("storePassword").toCharArray())
        val cert = ks.getCertificate(props.getProperty("keyAlias")) ?: throw GradleException("Alias ${props.getProperty("keyAlias")} not in the keystore.")
        val got = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        if (got != expected) throw GradleException("Release key certificate $got, expected $expected (signing/release-cert.sha256).")
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach { dependsOn(checkReleaseKey) }
