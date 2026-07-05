import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "xyz.limo060719.goclaw.baselineprofile"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        // Baseline-profile generation runs on API 28+ (it relies on ProfileInstaller).
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app under instrumentation.
    targetProjectPath = ":app"

    // A rooted AOSP emulator the generator can run on with no physical device attached.
    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Generate on a physical device plugged into this machine (both connected phones are
    // rooted & API 35/36, which the generator fully supports — far faster/more reliable than
    // downloading & booting the AOSP GMD emulator). To go back to the emulator instead, set
    // useConnectedDevices = false and add `managedDevices += "pixel6Api34"`.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.test.ext.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
