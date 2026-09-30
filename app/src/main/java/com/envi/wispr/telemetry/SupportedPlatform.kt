package com.envi.wispr.telemetry

/**
 * Whether this phone is one the app supports (#179). Google's review sandbox installs every Play upload
 * on a fake Android 11 phone, below `minSdk`; no supported phone can ever be below it, so keeping
 * telemetry off under it filters exactly that sandbox and nothing else. The threshold is the build's
 * own `minSdk` (`BuildConfig.MIN_SDK`), never a literal, so a raised floor moves it with no edit here.
 */
internal object SupportedPlatform {
    fun isBelowMinimum(sdkInt: Int, minSdk: Int): Boolean = sdkInt < minSdk
}
