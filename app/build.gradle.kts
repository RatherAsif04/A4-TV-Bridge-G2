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
        versionName = "1.4.2"
        versionCode = 13
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.merges += setOf("META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/DEPENDENCIES")
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
