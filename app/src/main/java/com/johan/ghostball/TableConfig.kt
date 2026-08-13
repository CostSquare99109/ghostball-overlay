package com.johan.ghostball

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists user-defined table pockets and table rectangle in [SharedPreferences].
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

    // ============ TABLE RECTANGLE (new v2) ============

    /** Table rectangle data class. */
    data class TableRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val screenWidth: Int,
        val screenHeight: Int
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
        fun clampX(x: Float): Float = x.coerceIn(left, right)
        fun clampY(y: Float): Float = y.coerceIn(top, bottom)
        fun clampPoint(x: Float, y: Float): Pair<Float, Float> = Pair(clampX(x), clampY(y))
    }

    /** Returns stored table rect if it matches current screen resolution, else null. */
    fun loadTableRect(currentWidth: Int, currentHeight: Int): TableRect? {
        val left = prefs.getFloat(KEY_TABLE_LEFT, -1f)
        val top = prefs.getFloat(KEY_TABLE_TOP, -1f)
        val right = prefs.getFloat(KEY_TABLE_RIGHT, -1f)
        val bottom = prefs.getFloat(KEY_TABLE_BOTTOM, -1f)
        val storedWidth = prefs.getInt(KEY_TABLE_SCREEN_W, -1)
        val storedHeight = prefs.getInt(KEY_TABLE_SCREEN_H, -1)

        if (left < 0f || top < 0f || right < 0f || bottom < 0f) return null
        if (storedWidth != currentWidth || storedHeight != currentHeight) return null
        if (right <= left || bottom <= top) return null

        return TableRect(left, top, right, bottom, storedWidth, storedHeight)
    }

    /** Saves table rectangle. */
    fun saveTableRect(rect: TableRect) {
        prefs.edit {
            putFloat(KEY_TABLE_LEFT, rect.left)
            putFloat(KEY_TABLE_TOP, rect.top)
            putFloat(KEY_TABLE_RIGHT, rect.right)
            putFloat(KEY_TABLE_BOTTOM, rect.bottom)
            putInt(KEY_TABLE_SCREEN_W, rect.screenWidth)
            putInt(KEY_TABLE_SCREEN_H, rect.screenHeight)
        }
    }

    /** Clears stored table rectangle. */
    fun clearTableRect() {
        prefs.edit {
            remove(KEY_TABLE_LEFT)
            remove(KEY_TABLE_TOP)
            remove(KEY_TABLE_RIGHT)
            remove(KEY_TABLE_BOTTOM)
            remove(KEY_TABLE_SCREEN_W)
            remove(KEY_TABLE_SCREEN_H)
        }
    }

    fun hasTableRect(): Boolean = prefs.contains(KEY_TABLE_LEFT)

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
        // Table rectangle keys
        const val KEY_TABLE_LEFT = "table_left"
        const val KEY_TABLE_TOP = "table_top"
        const val KEY_TABLE_RIGHT = "table_right"
        const val KEY_TABLE_BOTTOM = "table_bottom"
        const val KEY_TABLE_SCREEN_W = "table_screen_w"
        const val KEY_TABLE_SCREEN_H = "table_screen_h"
    }
}
