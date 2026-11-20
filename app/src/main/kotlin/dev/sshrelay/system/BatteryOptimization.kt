package dev.sshrelay.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption ("不受电池优化限制").
 *
 * Why a tunnel cares: since Android 6 the platform puts an app that is not on the
 * exemption list into Doze / the restricted standby bucket as soon as it leaves the
 * foreground, and OEM power managers (ColorOS, HyperOS, One UI, ...) are stricter
 * still. A frozen process stops servicing its sockets, so the SSH session dies even
 * though the foreground service is technically alive. Granting the exemption is the
 * single change with the largest effect on "背景一会儿就断线".
 *
 * The state is read from the platform on every check rather than persisted: the user
 * can revoke it in Settings at any time.
 */
object BatteryOptimization {

    /** Whether the system currently exempts this app from battery optimisation. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Entry points into the system's own UI, best first.
     *
     * 1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows the one-tap "允许应用
     *    在后台运行吗？" dialog scoped to this app. It needs
     *    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (declared in the manifest), and stock
     *    Android honours it; several OEM ROMs ignore or hide it, hence 2 and 3.
     * 2. The "电池优化" list page, where the user finds this app and switches it to
     *    "不优化".
     * 3. The app-details page - the one entry point every ROM has, and on ColorOS /
     *    HyperOS it is also where 自启动 / 后台运行 live.
     */
    fun settingsIntents(context: Context): List<Intent> {
        val packageUri = Uri.parse("package:${context.packageName}")
        return listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
    }

    /**
     * Launches the first settings page this device can actually open.
     *
     * Resolution is done by trying, not by `resolveActivity`: on Android 11+ package
     * visibility can make the query answer "no such activity" for an intent that
     * would in fact start fine.
     *
     * @return the intent that was launched, or null when every candidate failed.
     */
    fun openSettings(context: Context): Intent? = settingsIntents(context).firstNotNullOfOrNull { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.takeIf { runCatching { context.startActivity(it) }.isSuccess }
    }

    /**
     * The app-details page. Kept separate because it is useful even after the
     * exemption is granted: it is where OEM ROMs hide 自启动 / 后台运行 switches.
     */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
