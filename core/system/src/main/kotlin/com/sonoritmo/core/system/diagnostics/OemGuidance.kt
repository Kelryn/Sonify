package com.sonoritmo.core.system.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Device vendors known to kill background work more aggressively than AOSP does.
 *
 * This is not vendor-bashing, it is the difference between an app that works and an app
 * that gets a one-star review saying "stopped working after two days". No amount of correct
 * `AlarmManager` usage survives a vendor process killer, so RF-34 was promoted to **Must**:
 * the app must detect the vendor and walk the user to the specific screen that fixes it.
 */
enum class OemVendor(val aggressive: Boolean) {
    XIAOMI(aggressive = true),
    HUAWEI(aggressive = true),
    OPPO(aggressive = true),
    VIVO(aggressive = true),
    /** Aggressive, but far more recoverable: "Sleeping apps" is a single, findable toggle. */
    SAMSUNG(aggressive = true),
    OTHER(aggressive = false),
}

/**
 * What the user has to change, expressed as a topic rather than a sentence.
 *
 * The screen that explains this lives in `:feature:tools` and its wording lives in
 * `strings.xml`, in both supported languages. A hard-coded English sentence here would be
 * untranslatable and would violate the project's "no strings in code" rule.
 */
enum class OemGuidanceTopic {
    /** Allow the app to start itself after a reboot ("Autostart", "Auto-launch"). */
    AUTOSTART,

    /** Remove the app from the vendor's own battery/power manager restrictions. */
    VENDOR_BATTERY_MANAGER,

    /** Samsung's "Sleeping apps" / "Deep sleeping apps" list. */
    SLEEPING_APPS,

    /** The AOSP battery-optimisation allow list. */
    BATTERY_OPTIMIZATION,

    /** The app's own settings page, as the always-available fallback. */
    APP_DETAILS,
}

data class OemAction(val topic: OemGuidanceTopic, val intent: Intent)

/**
 * The per-vendor guidance catalogue.
 *
 * Every component name here is undocumented and can disappear in any vendor update, which
 * is why nothing is ever launched without [resolvableActions] checking it first, and why
 * [BATTERY_OPTIMIZATION] and [APP_DETAILS] — both AOSP, both guaranteed — always close the
 * list.
 */
object OemGuidance {

    /**
     * Vendor detection from `Build`.
     *
     * Both `MANUFACTURER` and `BRAND` are consulted: Redmi and POCO report Xiaomi as the
     * manufacturer, Honor devices predating the split report Huawei, and Realme and OnePlus
     * both run ColorOS derivatives with the same power manager.
     */
    fun detect(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND,
    ): OemVendor {
        val haystack = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            haystack.containsAny("xiaomi", "redmi", "poco") -> OemVendor.XIAOMI
            haystack.containsAny("huawei", "honor") -> OemVendor.HUAWEI
            haystack.containsAny("oppo", "realme", "oneplus") -> OemVendor.OPPO
            haystack.containsAny("vivo", "iqoo") -> OemVendor.VIVO
            haystack.containsAny("samsung") -> OemVendor.SAMSUNG
            else -> OemVendor.OTHER
        }
    }

    /**
     * Every candidate screen for a vendor, best first, followed by the two AOSP fallbacks.
     *
     * Several alternatives per vendor on purpose: the component was renamed between MIUI
     * versions, between ColorOS and OxygenOS, and between EMUI generations, and there is no
     * way to know which one this particular build has other than trying to resolve them.
     */
    fun actionsFor(vendor: OemVendor, packageName: String): List<OemAction> =
        vendorActions(vendor) + universalActions(packageName)

    /**
     * The subset of [actionsFor] that this device can actually open.
     *
     * Requires the vendor packages to be visible to us. From Android 11, package visibility
     * filtering hides them unless `:app` declares them in `<queries>`; if it does not, this
     * simply returns the AOSP fallbacks, which is a degradation and not a failure.
     */
    fun resolvableActions(context: Context, vendor: OemVendor): List<OemAction> {
        val packageManager = context.packageManager
        return actionsFor(vendor, context.packageName).filter { action ->
            packageManager.resolveActivity(action.intent, 0) != null
        }
    }

    private fun vendorActions(vendor: OemVendor): List<OemAction> = when (vendor) {
        OemVendor.XIAOMI -> listOf(
            action(OemGuidanceTopic.AUTOSTART, "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            action(OemGuidanceTopic.VENDOR_BATTERY_MANAGER, "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        )

        OemVendor.HUAWEI -> listOf(
            action(OemGuidanceTopic.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            action(OemGuidanceTopic.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            action(OemGuidanceTopic.VENDOR_BATTERY_MANAGER, "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )

        OemVendor.OPPO -> listOf(
            action(OemGuidanceTopic.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            action(OemGuidanceTopic.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            action(OemGuidanceTopic.AUTOSTART, "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // OnePlus ships the same restriction under its own package name.
            action(OemGuidanceTopic.VENDOR_BATTERY_MANAGER, "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )

        OemVendor.VIVO -> listOf(
            action(OemGuidanceTopic.AUTOSTART, "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            action(OemGuidanceTopic.VENDOR_BATTERY_MANAGER, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            action(OemGuidanceTopic.VENDOR_BATTERY_MANAGER, "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        )

        OemVendor.SAMSUNG -> listOf(
            action(OemGuidanceTopic.SLEEPING_APPS, "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            action(OemGuidanceTopic.SLEEPING_APPS, "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )

        OemVendor.OTHER -> emptyList()
    }

    /**
     * The two screens that exist on every Android device.
     *
     * Note what is *not* used to reach the first one: the app never declares
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. That permission lets an app pop the "allow?"
     * dialog directly, and it is a routine cause of Play rejection. Opening the system list
     * and letting the user find us achieves the same result with nothing declared at all.
     * See docs/02, amendment E-16.
     */
    private fun universalActions(packageName: String): List<OemAction> = listOf(
        OemAction(
            OemGuidanceTopic.BATTERY_OPTIMIZATION,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ),
        OemAction(
            OemGuidanceTopic.APP_DETAILS,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ),
    )

    private fun action(topic: OemGuidanceTopic, packageName: String, className: String): OemAction =
        OemAction(
            topic,
            Intent()
                .setComponent(ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    /**
     * The exact `<queries>` entries `:app` must declare for [resolvableActions] to see
     * anything on Android 11+. Listed here so the manifest and this catalogue cannot drift
     * apart silently.
     */
    val QUERIED_PACKAGES: List<String> = listOf(
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oppo.safe",
        "com.oneplus.security",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.samsung.android.lool",
    )
}
