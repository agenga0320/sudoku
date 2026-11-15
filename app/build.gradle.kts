import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from keystore.properties (not in version control); see README.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.sudoku"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.agenga0320.sudoku"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.3.1"
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// APKs are named e.g. Sudoku-v1.0.0-release.apk
base.archivesName.set("Sudoku-v${android.defaultConfig.versionName}")

dependencies {
    testImplementation("junit:junit:4.13.2")
}
