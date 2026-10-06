package com.envi.wispr.telemetry

/**
 * Whether this phone is one the app supports (#179, #427). Google's review sandbox installs every Play upload
 * on a fake Android 11 phone, below `minSdk`; no supported phone can ever be below it, so keeping telemetry
 * off under it filters exactly that sandbox and nothing else. The threshold is the build's own `minSdk`
 * (`BuildConfig.MIN_SDK`), never a literal, so a raised floor moves it with no edit here.
 *
 * The sandbox REPORTS an API level at or above `minSdk` while its framework is Android 11 (#427: its fatal
 * crashes are AndroidX `Api33Impl` branches, taken only when the reported level is 33 or higher, failing on
 * methods the framework lacks), so the reported level alone lets it through. The framework is therefore asked
 * too: the API 33 overload the sandbox crashed on must resolve. `SupportedPlatformTest` holds [PROBE_API] at or
 * below the gradle `minSdk`, so the probe can never filter a phone the app supports.
 */
internal object SupportedPlatform {
    /** The API level that added [PROBE_CLASS] and `PackageManager.getPackageInfo(String, PackageInfoFlags)`. */
    const val PROBE_API = 33

    /** `PackageManager.PackageInfoFlags`, the parameter type of the overload the sandbox lacks. */
    const val PROBE_CLASS = "android.content.pm.PackageManager\$PackageInfoFlags"

    fun isBelowMinimum(sdkInt: Int, minSdk: Int, frameworkHasProbeApi: Boolean): Boolean =
        sdkInt < minSdk || !frameworkHasProbeApi

    /** Resolves without initialising or invoking; any failure means the framework is older than [PROBE_API]. */
    fun frameworkHasProbeApi(): Boolean = runCatching {
        val loader = SupportedPlatform::class.java.classLoader
        val flags = Class.forName(PROBE_CLASS, false, loader)
        Class.forName("android.content.pm.PackageManager", false, loader)
            .getMethod("getPackageInfo", String::class.java, flags)
    }.isSuccess
}
