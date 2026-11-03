package com.chatmobile.prompt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.ZoneId
import java.util.Locale

/**
 * Android side of [RuntimeFacts]: resolves the real values and hands them to the
 * pure formatter.
 *
 * ## ★ Why every step is wrapped and every failure is non-fatal
 *
 * This runs on the send path. If anything here throws, the user loses the whole
 * message — for a time zone string. So:
 * - a missing permission is **not** an error, it is "location unavailable";
 * - a location provider that is off / has no fix is **not** an error either;
 * - anything unexpected is swallowed and logged, and the block still renders with
 *   whatever we do have.
 *
 * ## ★ Why coarse location, not fine
 *
 * The prompt says "approximate location". Coarse is enough to answer "what's the
 * weather where I am" and asks for far less than precise tracking. Fine location
 * would be an unjustified escalation of what the feature needs.
 */
object RuntimeFactsProvider {

    private const val TAG = "RuntimeFacts"

    /**
     * Build the facts block that gets appended as the last message.
     *
     * Never throws, never returns a partial/garbled line: worst case it returns a
     * block that says location is unavailable.
     */
    fun collect(context: Context, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = runCatching { ZoneId.systemDefault() }.getOrElse { ZoneId.of("UTC") }
        val locale = runCatching { Locale.getDefault() }.getOrElse { Locale.ENGLISH }
        val location = runCatching { lastKnownLocation(context) }
            .onFailure { Log.w(TAG, "定位读取失败，按不可用处理", it) }
            .getOrNull()
        return runCatching { RuntimeFacts.render(nowMillis, zone, locale, location) }
            .getOrElse {
                Log.w(TAG, "运行时信息渲染失败", it)
                ""
            }
    }

    /**
     * The most recent fix from any enabled provider, or `null`.
     *
     * ★ Prefers the **freshest** provider rather than a fixed order: a fresh
     *   network fix is more useful than a two-hour-old GPS one.
     * ★ `getLastKnownLocation` is a cheap in-memory read — it does not power on
     *   the radio and does not block, so it is safe on the send path. We are
     *   deliberately **not** requesting live updates: a chat turn does not need a
     *   fresh fix, and waking GNSS would cost battery for no benefit.
     */
    private fun lastKnownLocation(context: Context): RuntimeFacts.Location? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        val loc = best ?: return null
        return RuntimeFacts.Location(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
        )
    }
}
