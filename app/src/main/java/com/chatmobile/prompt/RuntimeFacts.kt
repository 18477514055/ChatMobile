package com.chatmobile.prompt

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **Runtime facts** — the things that are true *right now* and that the model
 * cannot know on its own: local time, time zone, locale, and (only if the user
 * granted permission) a coarse location.
 *
 * ## ★★★ Why this is a separate object and not part of [SystemPrompt]
 *
 * Because of the cache rule stated in [SystemPrompt]: anything that changes every
 * turn must live as far back in the message list as possible. The fixed prompt is
 * turn-invariant; these facts are turn-*variant* by definition. Folding them into
 * `SystemPrompt.STATIC` would invalidate the whole system prefix on every request.
 *
 * ⇒ They are appended as the **very last message**, where nothing is cached
 *   anyway, and where OpenAI's own guidance puts dynamic content
 *   ("put dynamic content at the end rather than the beginning").
 *
 * ## ★ Why [render] takes primitives instead of reading Android APIs
 *
 * So it is a **pure function**: no Context, no permission checks, no clock of its
 * own. The Android side ([RuntimeFactsProvider]) resolves the values and hands
 * them in — which is what makes the formatting unit-testable on a plain JVM.
 *
 * ## ★ Why location is explicitly marked unavailable instead of omitted
 *
 * If the block just silently lacks a location line, a model that has been told
 * "these facts are authoritative" will happily infer one from the time zone
 * ("it's Asia/Shanghai, so Beijing"). Saying **unavailable** is the only wording
 * that stops that, and it matches the honesty rule in the system prompt.
 */
object RuntimeFacts {

    /** How the location is rendered, when we have one. */
    data class Location(
        val latitude: Double,
        val longitude: Double,
        /** Horizontal accuracy in metres, when the platform reported one. */
        val accuracyMeters: Float? = null,
    )

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)", Locale.ENGLISH)

    /** Human label for the UTC offset, e.g. `UTC+08:00`. */
    private fun offsetLabel(at: ZonedDateTime): String {
        val total = at.offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return "UTC$sign" + h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }

    /** `39.9042` + `N` ⇒ `39.9042°N`. */
    private fun latLabel(lat: Double): String =
        String.format(Locale.US, "%.4f°%s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S")

    private fun lonLabel(lon: Double): String =
        String.format(Locale.US, "%.4f°%s", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

    /**
     * Render the facts block.
     *
     * @param epochMillis the moment the request is being sent
     * @param zone the device time zone
     * @param locale the device locale (answer-language hint, not a hard rule —
     *   the system prompt already says "answer in the user's language")
     * @param location `null` when the permission is not granted or nothing is known
     * @param workspace `null` when this conversation has no workspace bound.
     *   ★ The system prompt tells the model to read its permission level from
     *   *this block*, so a bound workspace must be stated here or the model is
     *   blind to its own boundaries.
     */
    fun render(
        epochMillis: Long,
        zone: ZoneId,
        locale: Locale,
        location: Location?,
        workspace: WorkspaceInfo? = null,
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val locLine = if (location == null) {
            "- Location: unavailable (no permission granted, or no fix yet) — do not guess it"
        } else {
            val acc = location.accuracyMeters?.let { " ±${it.toInt()} m" }.orEmpty()
            "- Approximate location: ${latLabel(location.latitude)}, ${lonLabel(location.longitude)}$acc"
        }
        val workspaceLines = if (workspace == null) {
            "- Workspace: none. This conversation is not bound to a directory, " +
                "so you have no files to read or write."
        } else {
            "- Workspace: \"${workspace.displayName}\"\n" +
                "  - Path: ${workspace.path}\n" +
                "  - Permission: ${workspace.permissionValue} — ${workspace.permissionHint}"
        }
        return """
            Runtime facts for this turn (authoritative; supplied by the client, not guessed):
            - Local time: ${zoned.format(TIME_FORMAT)}
            - Time zone: ${zone.id} (${offsetLabel(zoned)})
            - Device locale: ${locale.toLanguageTag()}
            $locLine
            $workspaceLines
        """.trimIndent()
    }

    /**
     * Workspace state **as the model must see it**.
     *
     * @param permissionValue one of the literal strings the system prompt names
     *   (`read-only` / `workspace-write` / `full-access`) — keeping the two in sync
     *   is what makes the "read your level from the runtime block" instruction work.
     */
    data class WorkspaceInfo(
        val displayName: String,
        val path: String,
        val permissionValue: String,
        val permissionHint: String,
    )
}
