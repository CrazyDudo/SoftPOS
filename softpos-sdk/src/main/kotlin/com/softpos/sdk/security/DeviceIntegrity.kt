package com.softpos.sdk.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import java.io.File

/** One observation about the device. A signal is a reason to look closer, not a verdict. */
enum class IntegritySignal(val label: String) {
    SU_BINARY_PRESENT("an su binary is present"),
    TEST_KEYS_BUILD("the system image is signed with test keys"),
    ROOT_MANAGEMENT_APP("a root management app is installed"),
    EMULATOR("running on an emulator"),
    APP_DEBUGGABLE("this app is a debuggable build"),
    DEBUGGER_ATTACHED("a debugger is attached"),
    ADB_ENABLED("USB debugging is enabled"),
    DEVELOPER_OPTIONS_ENABLED("developer options are enabled"),
    KEY_NOT_HARDWARE_BACKED("the card-data key is protected by software only"),
}

class IntegrityReport(
    val signals: Set<IntegritySignal>,
    val keySecurityLevel: KeySecurityLevel,
    val checkedAtEpochMillis: Long,
) {
    val clean: Boolean get() = signals.isEmpty()

    fun describe(): String =
        if (clean) "no integrity signals; key storage $keySecurityLevel" else
            "integrity signals: " + signals.joinToString { it.label } + "; key storage $keySecurityLevel"
}

/**
 * Local device integrity signals.
 *
 * ## What this is
 *
 * The checks a SoftPOS application can make about its own host without any server: whether the
 * device looks rooted, emulated or under a debugger, whether developer settings are open, and
 * whether the Keystore key protecting card data is in hardware. PCI MPoC's Attestation and
 * Monitoring requirements start from exactly these observations.
 *
 * ## What this is not
 *
 * It is not attestation. Attestation means a party the merchant does not control - Google's
 * Play Integrity, a hardware root of trust, an MPoC-certified monitoring service - vouches for
 * the device, and it means the answer cannot be forged by software running on that device. Every
 * check here runs on the device it judges, so a compromised device can lie about all of them.
 * Treat a signal as a reason to refuse or escalate, never treat a clean report as proof.
 *
 * Package-visibility filtering (API 30+) hides most root-management packages from an app that
 * does not declare them in its manifest, so [IntegritySignal.ROOT_MANAGEMENT_APP] is best effort.
 */
object DeviceIntegrity {

    fun assess(context: Context, crypto: KeystoreCryptoService): IntegrityReport {
        val signals = LinkedHashSet<IntegritySignal>()

        if (SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }) {
            signals += IntegritySignal.SU_BINARY_PRESENT
        }
        if (Build.TAGS?.contains("test-keys") == true) signals += IntegritySignal.TEST_KEYS_BUILD
        if (ROOT_PACKAGES.any { context.hasPackage(it) }) signals += IntegritySignal.ROOT_MANAGEMENT_APP
        if (looksLikeEmulator()) signals += IntegritySignal.EMULATOR
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            signals += IntegritySignal.APP_DEBUGGABLE
        }
        if (Debug.isDebuggerConnected()) signals += IntegritySignal.DEBUGGER_ATTACHED
        if (context.globalSetting(Settings.Global.ADB_ENABLED)) signals += IntegritySignal.ADB_ENABLED
        if (context.globalSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) {
            signals += IntegritySignal.DEVELOPER_OPTIONS_ENABLED
        }

        val keyLevel = runCatching { crypto.keySecurityLevel() }.getOrDefault(KeySecurityLevel.UNKNOWN)
        if (keyLevel == KeySecurityLevel.SOFTWARE) signals += IntegritySignal.KEY_NOT_HARDWARE_BACKED

        return IntegrityReport(signals, keyLevel, System.currentTimeMillis())
    }

    private fun looksLikeEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            manufacturer.contains("Genymotion")
    }

    private fun Context.hasPackage(name: String): Boolean = runCatching {
        packageManager.getPackageInfo(name, 0)
        true
    }.getOrElse { it !is PackageManager.NameNotFoundException && false }

    private fun Context.globalSetting(name: String): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, name, 0) == 1
    }.getOrDefault(false)

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
    )

    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
    )
}
