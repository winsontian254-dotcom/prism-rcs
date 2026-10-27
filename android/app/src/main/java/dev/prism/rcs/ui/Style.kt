// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package dev.prism.rcs.ui

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The launcher's look, as Prism Launcher hands it over each time it opens this app (intent extras
 * named "style.*", listed in the README). The last one received is kept, so opening the app from
 * its own icon looks the same. Without Prism it's Windows Phone 8.1 in dark with cobalt.
 */
data class Style(
    val dark: Boolean = true,
    val accent: Int = 0xFF0050EF.toInt(),
    /** "system", or a Google Fonts family: "noto sans", "open sans", "source sans 3", ... */
    val font: String = "system",
    val textScale: Float = 1f,
    /** "wp7", "wp8", "wp81" or "w10m". */
    val phone: String = "wp81",
    /** Headings all in lower case (7, 8, 8.1) or in sentence case (10 Mobile). */
    val lowercase: Boolean = true,
    /** "RINGS_SOLID" (7, 8), "RINGS_TINT" (8.1) or "GLYPHS" (10 Mobile). */
    val appBar: String = "RINGS_TINT",
    val appBarLabels: Boolean = false,
    /** Messaging's design: "8" (7 and 8), "81" (8.1) or "10" (10 Mobile). */
    val messaging: String = "81",
    val tilt: Boolean = true,
    val turnstile: Boolean = true,
    /** Animation speed: 1 normal, 2 twice as fast. */
    val speed: Float = 1f,
) {
    fun toJson(): String = JSONObject()
        .put("dark", dark).put("accent", accent).put("font", font).put("textScale", textScale.toDouble())
        .put("phone", phone).put("lowercase", lowercase).put("appBar", appBar).put("appBarLabels", appBarLabels)
        .put("messaging", messaging).put("tilt", tilt).put("turnstile", turnstile).put("speed", speed.toDouble())
        .toString()

    /** This style with whatever [intent] carries laid over it. */
    fun with(intent: Intent): Style {
        val e = intent.extras ?: return this
        if (e.keySet().none { it.startsWith("style.") || it == "accent" }) return this
        fun b(k: String, d: Boolean) = if (e.containsKey(k)) e.getBoolean(k, d) else d
        fun f(k: String, d: Float) = if (e.containsKey(k)) e.getFloat(k, d) else d
        fun s(k: String, d: String) = e.getString(k) ?: d
        return copy(
            dark = b("style.dark", dark),
            // "accent" alone is the first version of the hand-off
            accent = if (e.containsKey("style.accent")) e.getInt("style.accent") else if (e.containsKey("accent")) e.getInt("accent") else accent,
            font = s("style.font", font),
            textScale = f("style.text_scale", textScale),
            phone = s("style.phone", phone),
            lowercase = b("style.lowercase", lowercase),
            appBar = s("style.app_bar", appBar),
            appBarLabels = b("style.app_bar_labels", appBarLabels),
            messaging = s("style.messaging", messaging),
            tilt = b("style.tilt", tilt),
            turnstile = b("style.turnstile", turnstile),
            speed = f("style.speed", speed).coerceIn(0.25f, 4f),
        )
    }

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("style", Context.MODE_PRIVATE)

        fun load(c: Context): Style = runCatching {
            val o = JSONObject(prefs(c).getString("style", null) ?: return Style())
            val d = Style()
            Style(
                o.optBoolean("dark", d.dark), o.optInt("accent", d.accent), o.optString("font", d.font),
                o.optDouble("textScale", 1.0).toFloat(), o.optString("phone", d.phone), o.optBoolean("lowercase", d.lowercase),
                o.optString("appBar", d.appBar), o.optBoolean("appBarLabels", d.appBarLabels), o.optString("messaging", d.messaging),
                o.optBoolean("tilt", d.tilt), o.optBoolean("turnstile", d.turnstile), o.optDouble("speed", 1.0).toFloat(),
            )
        }.getOrDefault(Style())

        fun save(c: Context, s: Style) = prefs(c).edit().putString("style", s.toJson()).apply()
    }
}
