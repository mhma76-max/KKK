package com.abdulrahman.chessassistant

/**
 * مولّد صور اصطناعية للرقعة (ليست sprites حقيقية من Chess.com). يرسم رقعة بألوان Chess.com الخضراء المرجعية،
 * وإحداثيات صغيرة في الزوايا، وقطعاً بأشكال مميزة لكل نوع (مخطّط + تعبئة). يختبر منطق الاكتشاف والتعرف فقط.
 */
object SyntheticBoard {
    private val LIGHT = BoardTheme.rgb(238, 238, 210)
    private val DARK = BoardTheme.rgb(118, 150, 86)
    private val BG = BoardTheme.rgb(49, 46, 43)

    private fun inRect(u: Float, v: Float, a: Float, b: Float, c: Float, d: Float) = u in a..b && v in c..d

    fun inShape(type: Char, u: Float, v: Float): Boolean = when (type) {
        'p' -> (u - 0.5f) * (u - 0.5f) + (v - 0.55f) * (v - 0.55f) <= 0.20f * 0.20f || inRect(u, v, 0.30f, 0.70f, 0.72f, 0.88f)
        'n' -> inRect(u, v, 0.30f, 0.45f, 0.20f, 0.85f) || inRect(u, v, 0.30f, 0.72f, 0.20f, 0.38f) || inRect(u, v, 0.30f, 0.70f, 0.72f, 0.86f)
        'b' -> (v in 0.15f..0.80f && kotlin.math.abs(u - 0.5f) <= 0.06f + (v - 0.15f) * 0.30f) || inRect(u, v, 0.30f, 0.70f, 0.80f, 0.88f)
        'r' -> inRect(u, v, 0.28f, 0.72f, 0.32f, 0.86f) || inRect(u, v, 0.28f, 0.38f, 0.18f, 0.32f) ||
            inRect(u, v, 0.45f, 0.55f, 0.18f, 0.32f) || inRect(u, v, 0.62f, 0.72f, 0.18f, 0.32f)
        'q' -> (u - 0.5f) * (u - 0.5f) + (v - 0.2f) * (v - 0.2f) <= 0.07f * 0.07f ||
            (v in 0.28f..0.84f && kotlin.math.abs(u - 0.5f) <= 0.08f + (v - 0.28f) * 0.45f)
        'k' -> inRect(u, v, 0.46f, 0.54f, 0.10f, 0.34f) || inRect(u, v, 0.40f, 0.60f, 0.18f, 0.25f) ||
            inRect(u, v, 0.30f, 0.70f, 0.34f, 0.86f)
        else -> false
    }

    private fun colorFor(piece: Char, outline: Boolean): Int =
        if (Rules.isWhite(piece)) (if (outline) BoardTheme.rgb(30, 30, 30) else BoardTheme.rgb(250, 250, 250))
        else (if (outline) BoardTheme.rgb(15, 15, 15) else BoardTheme.rgb(75, 75, 75))

    /** pos: فهرس rank*8+file. flipped = الأسود في الأسفل. */
    fun render(
        width: Int, height: Int, left: Int, top: Int, cell: Int,
        pos: CharArray, flipped: Boolean = false, dots: Set<Int> = emptySet(),
        light: Int = LIGHT, dark: Int = DARK
    ): Frame {
        val px = IntArray(width * height) { BG }
        for (row in 0..7) for (col in 0..7) {
            val file = if (flipped) 7 - col else col
            val rank = if (flipped) row else 7 - row
            val idx = rank * 8 + file
            val isDark = (file + rank) % 2 == 0
            val base = if (isDark) dark else light
            val label = if (isDark) light else dark
            val piece = pos[idx]
            val x0 = left + col * cell
            val y0 = top + row * cell
            for (y in 0 until cell) for (x in 0 until cell) {
                val u = x.toFloat() / cell
                val v = y.toFloat() / cell
                var c = base
                // إحداثيات: زاوية يسار-أعلى لعمود a، ويمين-أسفل لصف 1
                if (col == 0 && u < 0.14f && v < 0.16f && u > 0.04f && v > 0.04f) c = label
                if (row == 7 && u > 0.84f && v > 0.82f && u < 0.96f && v < 0.96f) c = label
                if (idx in dots && (u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f) <= 0.15f * 0.15f) c = BoardTheme.rgb(90, 120, 70)
                if (piece != '.') {
                    val t = piece.lowercaseChar()
                    if (inShape(t, u, v)) {
                        val e = 0.035f
                        val edge = !inShape(t, u + e, v) || !inShape(t, u - e, v) || !inShape(t, u, v + e) || !inShape(t, u, v - e)
                        c = colorFor(piece, edge)
                    }
                }
                px[(y0 + y) * width + x0 + x] = c
            }
        }
        return Frame(px, width, height)
    }

    fun fromFen(fen: String): CharArray = Rules.fromFen(fen)!!.sq
}
