package com.abdulrahman.chessassistant

/** لوحة 8x8: الصف 0 = الرانك 8. '.' = مربع فارغ، أحرف كبيرة = أبيض، صغيرة = أسود. */
typealias Board = Array<CharArray>

object Fen {
    private const val PIECES = "KQRBNPkqrbnp"

    fun emptyBoard(): Board = Array(8) { CharArray(8) { '.' } }

    fun toFen(
        board: Board,
        whiteToMove: Boolean = true,
        castling: String = "-",
        enPassant: String = "-",
        halfmove: Int = 0,
        fullmove: Int = 1
    ): String {
        require(board.size == 8 && board.all { it.size == 8 }) { "Board must be 8x8" }
        val rows = board.joinToString("/") { row ->
            val sb = StringBuilder()
            var empty = 0
            for (c in row) {
                if (c == '.') {
                    empty++
                } else {
                    require(c in PIECES) { "Invalid piece: $c" }
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(c)
                }
            }
            if (empty > 0) sb.append(empty)
            sb.toString()
        }
        val side = if (whiteToMove) "w" else "b"
        return "$rows $side ${castling.ifEmpty { "-" }} $enPassant $halfmove $fullmove"
    }
}

/**
 * نقلة بصيغة UCI مثل e2e4 (مع ترقية اختيارية).
 * الملفات 0..7 = a..h، الرانك 0..7 = 1..8، والفهرس = rank*8 + file.
 */
data class Move(val fromFile: Int, val fromRank: Int, val toFile: Int, val toRank: Int, val promotion: Char? = null) {
    val from: Int get() = fromRank * 8 + fromFile
    val to: Int get() = toRank * 8 + toFile

    fun uci(): String = "${'a' + fromFile}${'1' + fromRank}${'a' + toFile}${'1' + toRank}${promotion ?: ""}"

    companion object {
        private val UCI = Regex("^([a-h])([1-8])([a-h])([1-8])([qrbn])?$")

        fun parseUci(text: String): Move? {
            val m = UCI.matchEntire(text.trim().lowercase()) ?: return null
            val g = m.groupValues
            return Move(g[1][0] - 'a', g[2][0] - '1', g[3][0] - 'a', g[4][0] - '1', g[5].firstOrNull())
        }

        fun of(from: Int, to: Int, promotion: Char? = null): Move =
            Move(from % 8, from / 8, to % 8, to / 8, promotion)
    }
}

/** موضع الرقعة على الشاشة بالبكسل. flipped = الأسود في الأسفل. */
data class BoardGeometry(val left: Float, val top: Float, val size: Float, val flipped: Boolean = false) {
    /** مركز المربع بإحداثيات الشاشة. */
    fun center(file: Int, rank: Int): Pair<Float, Float> {
        val cell = size / 8f
        val col = if (flipped) 7 - file else file
        val row = if (flipped) rank else 7 - rank
        return Pair(left + (col + 0.5f) * cell, top + (row + 0.5f) * cell)
    }
}
