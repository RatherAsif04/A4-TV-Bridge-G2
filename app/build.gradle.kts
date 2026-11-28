plugins {
    id("com.android.application")
}

android {
    namespace = "dev.a4tvbridge.independent"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.a4tvbridge.app"
        minSdk = 26
        targetSdk = 36
        versionName = "1.4.4"
        versionCode = 15
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf("**/x86/**")
        }
        resources {
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages*.properties",
                "META-INF/androidx/**",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/com.android.tools/**",
                "kotlin/**/*.kotlin_builtins",
                "kotlin/**/*.kotlin_metadata",
                "DebugProbesKt.bin"
            )
            merges += setOf("META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    constraints {
        implementation("androidx.annotation:annotation:1.9.1")
        implementation("androidx.collection:collection:1.5.0")
        implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
        implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    }
    implementation("com.flyfishxu:kadb:2.1.4")
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

configurations.configureEach { exclude(group = "org.lsposed.hiddenapibypass") }
