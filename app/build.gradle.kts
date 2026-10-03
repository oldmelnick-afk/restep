plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.rocketglasses.soberyobratno"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = if (providers.gradleProperty("isolatedInputTest").isPresent)
            "com.rocketglasses.soberyobratno.inputtest" else "com.rocketglasses.soberyobratno"
        minSdk = 28
        targetSdk = 33
        versionCode = 18
        versionName = "0.18"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
