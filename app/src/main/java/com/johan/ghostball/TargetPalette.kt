package com.johan.ghostball

/**
 * Fixed palette used to color-code every detected object ball in the overlay.
 * Deliberately:
 *  - no white (reserved for the cue ball), and
 *  - no green-ish tones (pool felt is typically green/blue — this makes each
 *    assigned color stand out against the background without dynamic felt
 *    analysis).
 *
 * The color is purely a visual differentiator for the overlay; it does NOT
 * attempt to identify the real color of the ball in the game.
 */
object TargetPalette {

    val colors: List<Int> = listOf(
        0xFFE53935.toInt(), // Rojo
        0xFFFB8C00.toInt(), // Naranja
        0xFFFDD835.toInt(), // Amarillo fuerte
        0xFFC0CA33.toInt(), // Lima
        0xFF00ACC1.toInt(), // Cian
        0xFF1E88E5.toInt(), // Azul
        0xFF8E24AA.toInt(), // Violeta
        0xFFD81B60.toInt(), // Magenta
        0xFFEC407A.toInt(), // Rosa
        0xFF00897B.toInt()  // Teal
    )

    /** Cycles when more balls than colors. */
    fun color(index: Int): Int = colors[index.mod(colors.size)]
}