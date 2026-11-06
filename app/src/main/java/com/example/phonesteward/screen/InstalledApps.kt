package com.example.phonesteward.screen

import android.content.Context
import java.util.Locale

/**
 * A14: the apps the phone-use Rules screen works with. The stored allow and deny lists stay
 * package ids (A14.5); everything here only turns ids into names for the owner.
 */

/** One launchable app: its package id and its launcher label. */
data class AppEntry(val packageName: String, val label: String)

/**
 * The apps this phone can launch, and the name of an installed package.
 * The real adapter reads PackageManager; tests and the end-to-end flows install a fake
 * through `AppGraph.installedApps`.
 */
interface InstalledApps {
    /** The phone's apps with a launcher entry, in any order; [AppPickerLogic] sorts. Icons are not part of this: [icon] loads them lazily. */
    fun launchable(): List<AppEntry>

    /**
     * A15.11: every installed package, including ones with no launcher entry (Google Play
     * services, the owner's third Maps attempt), so the picker's Show system apps switch can
     * offer them. The default is the launchable set, for fakes that only know about those.
     */
    fun installed(): List<AppEntry> = launchable()

    /** The launcher label of an installed package, or null when it is not installed. */
    fun label(packageName: String): String?

    /** The app's icon, fetched on demand (never eagerly with [launchable]); null when there is none. */
    fun icon(packageName: String): android.graphics.drawable.Drawable?
}

/** A14.1: how a stored entry reads on screen — the name with the id under it, or the id and "not installed". */
data class GuardRowText(val title: String, val subtitle: String?) {
    companion object {
        const val NOT_INSTALLED = "not installed"

        /** A resolved lookup decides name+id vs id+"not installed". */
        fun of(packageName: String, label: String?): GuardRowText =
            of(packageName, label, resolved = true)

        /**
         * Correction 1 (P14): a package missing from the current label map is still resolving
         * (a just-picked app) — it reads as the id alone, never as "not installed"; only a
         * lookup that actually answered null earns that mark.
         */
        fun of(packageName: String, label: String?, resolved: Boolean): GuardRowText =
            when {
                !resolved -> GuardRowText(packageName, null)
                label == null -> GuardRowText(packageName, NOT_INSTALLED)
                else -> GuardRowText(label, packageName)
            }
    }
}

/**
 * A14.2/A14.3: the Add-an-app picker's pure logic. The picker shows the phone's launchable apps
 * sorted by name, filtered by a search box that matches name or id, without the apps already on
 * the target list. In the allow picker an always-denied app (the floor, A14.3) is shown with
 * "always denied" and cannot be added.
 */
object AppPickerLogic {

    /** One row of the picker. */
    data class Row(val app: AppEntry, val alwaysDenied: Boolean, val onOtherList: Boolean) {
        val addable: Boolean get() = !alwaysDenied
    }

    fun matches(app: AppEntry, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return app.label.lowercase(Locale.ROOT).contains(q) || app.packageName.lowercase(Locale.ROOT).contains(q)
    }

    /**
     * [exclude] holds the apps already on the target list (left out); [otherList] holds the opposite
     * list, so a row there can be marked "on …" and still moves on tap (A14.2).
     */
    fun rows(apps: List<AppEntry>, query: String, exclude: Set<String>, otherList: Set<String>, forAllow: Boolean): List<Row> =
        apps.asSequence()
            .filter { it.packageName !in exclude }
            .filter { matches(it, query) }
            .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
            .map {
                Row(
                    it,
                    alwaysDenied = forAllow && GuardPolicy.alwaysDenied(it.packageName),
                    onOtherList = it.packageName in otherList,
                )
            }
            .toList()
}

/**
 * A14.2: the real [InstalledApps], read from PackageManager. Callers keep it off the main thread
 * (the picker calls it inside `withContext(Dispatchers.IO)`). Icons are loaded lazily, one
 * `icon()` call at a time, so opening the picker never decodes every icon at once.
 */
class AndroidInstalledApps(private val context: Context) : InstalledApps {

    private val pm: android.content.pm.PackageManager = context.applicationContext.packageManager

    override fun launchable(): List<AppEntry> {
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(main, android.content.pm.PackageManager.MATCH_ALL)
                .map { resolve ->
                    AppEntry(
                        packageName = resolve.activityInfo.packageName,
                        label = resolve.loadLabel(pm).toString().trim(),
                    )
                }
                .filter { it.packageName.isNotEmpty() }
                .distinctBy { it.packageName }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * A15.11: the phone's installed packages, launcher entry or not; the steward's own app is
     * left out (it can only ever be read, never used), and a package with no label reads by id.
     */
    override fun installed(): List<AppEntry> = try {
        pm.getInstalledPackages(0)
            .asSequence()
            .map { it.packageName }
            .filter { it.isNotEmpty() && it != context.packageName }
            .distinct()
            .map { pkg -> AppEntry(pkg, label(pkg) ?: pkg) }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    override fun label(packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().trim().ifEmpty { null }
    } catch (_: Exception) {
        null // NameNotFoundException, or a removed package: the row shows "not installed" (A14.1).
    }

    override fun icon(packageName: String): android.graphics.drawable.Drawable? = try {
        pm.getApplicationInfo(packageName, 0).loadIcon(pm)
    } catch (_: Exception) {
        null // NameNotFoundException, or a drawable that cannot load: the row falls back to a letter avatar.
    }
}
