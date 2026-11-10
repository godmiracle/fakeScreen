plugins {
    alias(libs.plugins.agp.app)
}

val releaseKeystore = providers.environmentVariable("FAKESCREEN_KEYSTORE_FILE").orNull
    ?: rootProject.file(".signing/fakescreen-release.p12").absolutePath
val releasePassword = providers.environmentVariable("FAKESCREEN_KEYSTORE_PASSWORD").orNull
    ?: rootProject.file(".signing/keystore-password").takeIf { it.isFile }?.readText()?.trim()

android {
    namespace = "top.chuyb.fakeScreen"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.godmiracle.fakescreen"
        targetSdk = 37
        minSdk = 35
        versionCode = 25
        versionName = "2.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystore)
            storePassword = releasePassword
            storeType = "PKCS12"
            keyAlias = "fakescreen-release"
            keyPassword = releasePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["release"]
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.core)
    implementation(libs.appcompat)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
