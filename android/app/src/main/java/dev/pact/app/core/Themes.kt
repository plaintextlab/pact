package dev.pact.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Color schemes from spec/themes.json (shared with the desktop app, bundled as an asset). */
@Serializable
data class Palette(
    val bg: String, val bg1: String, val bg2: String, val fg: String, val muted: String, val primary: String,
    val red: String, val orange: String, val yellow: String, val green: String, val aqua: String, val blue: String, val purple: String,
) {
    /** Group color: theme key (`blue`) or `#RRGGBB`. */
    fun named(key: String): String = when (key) {
        "red" -> red; "orange" -> orange; "yellow" -> yellow; "green" -> green
        "aqua" -> aqua; "blue" -> blue; "purple" -> purple
        else -> if (key.startsWith("#")) key else primary
    }
}

@Serializable
data class ThemeDef(val id: String, val name: String, val dark: Palette, val light: Palette)

object Themes {
    @Volatile private var cache: List<ThemeDef>? = null

    fun all(context: Context): List<ThemeDef> = cache ?: context.assets.open("themes.json").bufferedReader().use {
        PactJson.decodeFromString(ListSerializer(ThemeDef.serializer()), it.readText())
    }.also { cache = it }

    fun get(context: Context, id: String): ThemeDef = all(context).let { list -> list.firstOrNull { it.id == id } ?: list.first() }
}

fun hexColor(s: String): Long = 0xFF000000 or s.removePrefix("#").toLong(16)
