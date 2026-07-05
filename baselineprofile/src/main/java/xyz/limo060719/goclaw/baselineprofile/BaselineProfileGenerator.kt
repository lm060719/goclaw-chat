package xyz.limo060719.goclaw.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile. The AndroidX Baseline Profile Gradle plugin runs this on a
 * device (a connected phone here — see `baselineprofile/build.gradle.kts`), records which code the
 * app touches during the journey below, and bakes it into `app/src/.../generated/baselineProfiles`
 * so ART can AOT-compile the hot paths at install time (faster cold start + smoother first scroll).
 *
 * The journey is deliberately configuration-free: a freshly installed app isn't connected to a
 * gateway, so we exercise what always renders — cold start of the chat screen, then a couple of
 * scroll gestures to warm the list/Compose paths. `includeInStartupProfile = true` additionally
 * emits a startup profile for the earliest launch frames.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Let the first frame settle, then warm the scroll container a few times. Guarded so the
        // profile run never fails on a device/screen where the scrollable isn't present.
        device.waitForIdle()
        val scrollable = device.wait(Until.findObject(By.scrollable(true)), 3_000)
        if (scrollable != null) {
            scrollable.setGestureMargin(device.displayWidth / 5)
            repeat(2) {
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
            }
            scrollable.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "xyz.limo060719.goclaw"
    }
}
