import java.util.Properties

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
    // An update signed by another key is refused by Android: the APKs for the TV use one key, kept off the repository.
    val stable = File(System.getenv("S4F_SIGNING") ?: "${System.getProperty("user.home")}/.config/streaming4free/signing/signing.properties")
    if (stable.isFile) signingConfigs.create("stable") {
        val p = Properties().apply { stable.inputStream().use { load(it) } }
        storeFile = file(p.getProperty("storeFile"))
        storePassword = p.getProperty("storePassword")
        keyAlias = p.getProperty("keyAlias")
        keyPassword = p.getProperty("keyPassword")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // same key as the two other apps: the stable key kept outside the repository, else (CI) the debug key
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
