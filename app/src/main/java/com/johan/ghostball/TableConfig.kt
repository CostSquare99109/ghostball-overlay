package com.johan.ghostball

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists user-defined table pockets in [SharedPreferences] so the overlay can
 * restore them between sessions.
 *
 * Stored as a semicolon-separated string of `x,y` pairs (no labels — labels are
 * assigned by the caller based on order: corners first, then mids).
 */
class TableConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Maximum pockets the user can mark. Standard pool table = 6. */
    val pocketCount: Int get() = POCKETS_MAX

    /** Returns currently-stored pockets, or null if the user hasn't defined any. */
    fun loadPockets(): List<ShotCalculator.Pocket>? {
        val raw = prefs.getString(KEY_POCKETS, null) ?: return null
        val points = raw.split(';')
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(',')
                if (parts.size != 2) return@mapNotNull null
                val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
                val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
                ShotCalculator.Point(x, y)
            }
        if (points.size != POCKETS_MAX) return null

        return points.mapIndexed { idx, p ->
            ShotCalculator.Pocket(p.x, p.y, defaultName(idx))
        }
    }

    /** Saves a full set of pockets, replacing whatever was there. */
    fun savePockets(pockets: List<ShotCalculator.Pocket>) {
        require(pockets.size == POCKETS_MAX) {
            "Need exactly $POCKETS_MAX pockets, got ${pockets.size}"
        }
        val raw = pockets.joinToString(";") { "${it.x},${it.y}" }
        prefs.edit { putString(KEY_POCKETS, raw) }
    }

    fun clearPockets() {
        prefs.edit { remove(KEY_POCKETS) }
    }

    fun hasPockets(): Boolean = prefs.contains(KEY_POCKETS)

    private fun defaultName(idx: Int): String = when (idx) {
        0 -> "Esquina inf. izq."
        1 -> "Esquina inf. der."
        2 -> "Esquina sup. izq."
        3 -> "Esquina sup. der."
        4 -> "Media inferior"
        5 -> "Media superior"
        else -> "Tronera $idx"
    }

    companion object {
        const val PREFS_NAME = "ghostball_prefs"
        const val KEY_POCKETS = "user_pockets"
        const val POCKETS_MAX = 6
    }
}
