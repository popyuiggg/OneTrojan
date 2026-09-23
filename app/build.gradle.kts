plugins {
    id("com.android.application")
}

android {
    namespace = "app.onetrojan"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.onetrojan"
        minSdk = 30
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.0"

        testInstrumentationRunner = "android.app.Instrumentation"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            ndkBuild {
                arguments += "NDK_APPLICATION_MK=${file("../third_party/hev-socks5-tunnel/Application.mk").absolutePath}"
                targets += "hev-socks5-tunnel"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("../third_party/hev-socks5-tunnel/Android.mk")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(files("libs/utlsbridge.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
