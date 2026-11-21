package com.abdulrahman.chessassistant

import kotlin.math.abs

/**
 * وضعية شطرنج كاملة. الفهرس = rank*8 + file (a1 = 0، h8 = 63).
 * أحرف كبيرة = أبيض، صغيرة = أسود، '.' = فارغ.
 */
class Position(
    val sq: CharArray = CharArray(64) { '.' },
    val whiteToMove: Boolean = true,
    val castling: String = "-",
    val ep: Int = -1,
    val halfmove: Int = 0,
    val fullmove: Int = 1
) {
    fun withTurn(white: Boolean): Position = Position(sq, white, castling, -1, halfmove, fullmove)
}

object Rules {
    private val KNIGHT = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
        intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2)
    )
    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    fun startPosition(): Position = fromFen(START_FEN)!!

    fun isWhite(c: Char) = c in 'A'..'Z'

    fun fromFen(fen: String): Position? {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        val rows = parts[0].split("/")
        if (rows.size != 8) return null
        val sq = CharArray(64) { '.' }
        for ((i, row) in rows.withIndex()) {
            val rank = 7 - i
            var file = 0
            for (ch in row) {
                if (ch in '1'..'8') {
                    file += ch - '0'
                } else if (ch in "KQRBNPkqrbnp") {
                    if (file > 7) return null
                    sq[rank * 8 + file] = ch
                    file++
                } else return null
            }
            if (file != 8) return null
        }
        val white = when (parts[1]) { "w" -> true; "b" -> false; else -> return null }
        val castling = if (parts[2] == "-") "-" else parts[2]
        val ep = if (parts[3] == "-") -1 else {
            if (parts[3].length != 2 || parts[3][0] !in 'a'..'h' || parts[3][1] !in '1'..'8') return null
            (parts[3][1] - '1') * 8 + (parts[3][0] - 'a')
        }
        val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
        val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
        return Position(sq, white, castling, ep, half, full)
    }

    fun toFen(p: Position): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val c = p.sq[rank * 8 + file]
                if (c == '.') empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(c)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(if (p.whiteToMove) " w " else " b ")
        sb.append(if (p.castling.isEmpty()) "-" else p.castling)
        sb.append(' ')
        sb.append(if (p.ep < 0) "-" else "${'a' + p.ep % 8}${'1' + p.ep / 8}")
        sb.append(' ').append(p.halfmove).append(' ').append(p.fullmove)
        return sb.toString()
    }

    fun kingSquare(sq: CharArray, white: Boolean): Int {
        val k = if (white) 'K' else 'k'
        for (i in 0..63) if (sq[i] == k) return i
        return -1
    }

    fun isAttacked(sq: CharArray, target: Int, byWhite: Boolean): Boolean {
        val tf = target % 8
        val tr = target / 8
        // بيادق
        val pr = if (byWhite) tr - 1 else tr + 1
        if (pr in 0..7) {
            val pawn = if (byWhite) 'P' else 'p'
            if (tf > 0 && sq[pr * 8 + tf - 1] == pawn) return true
            if (tf < 7 && sq[pr * 8 + tf + 1] == pawn) return true
        }
        // أحصنة
        val knight = if (byWhite) 'N' else 'n'
        for (d in KNIGHT) {
            val f = tf + d[0]
            val r = tr + d[1]
            if (f in 0..7 && r in 0..7 && sq[r * 8 + f] == knight) return true
        }
        // ملك + قطع منزلقة
        for (dr in -1..1) for (df in -1..1) {
            if (dr == 0 && df == 0) continue
            var f = tf + df
            var r = tr + dr
            var first = true
            while (f in 0..7 && r in 0..7) {
                val c = sq[r * 8 + f]
                if (c != '.') {
                    if (isWhite(c) == byWhite) {
                        val lc = c.lowercaseChar()
                        val diag = df != 0 && dr != 0
                        if (lc == 'q' || (diag && lc == 'b') || (!diag && lc == 'r') || (first && lc == 'k')) return true
                    }
                    break
                }
                first = false
                f += df
                r += dr
            }
        }
        return false
    }

    fun inCheck(p: Position, white: Boolean): Boolean {
        val k = kingSquare(p.sq, white)
        return k >= 0 && isAttacked(p.sq, k, !white)
    }

    fun legalMoves(p: Position): List<Move> {
        val white = p.whiteToMove
        val sq = p.sq
        val pseudo = ArrayList<Move>(48)
        fun add(from: Int, to: Int) { pseudo.add(Move.of(from, to)) }
        fun addPawn(from: Int, to: Int) {
            val last = to / 8 == 7 || to / 8 == 0
            if (last) for (pr in "qrbn") pseudo.add(Move.of(from, to, pr)) else add(from, to)
        }
        for (from in 0..63) {
            val c = sq[from]
            if (c == '.' || isWhite(c) != white) continue
            val f = from % 8
            val r = from / 8
            when (c.lowercaseChar()) {
                'p' -> {
                    val dir = if (white) 1 else -1
                    val startRank = if (white) 1 else 6
                    val nr = r + dir
                    if (nr in 0..7) {
                        if (sq[nr * 8 + f] == '.') {
                            addPawn(from, nr * 8 + f)
                            if (r == startRank && sq[(r + 2 * dir) * 8 + f] == '.') add(from, (r + 2 * dir) * 8 + f)
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val t = nr * 8 + nf
                            val tc = sq[t]
                            if (tc != '.' && isWhite(tc) != white) addPawn(from, t)
                            else if (tc == '.' && t == p.ep) add(from, t)
                        }
                    }
                }
                'n' -> for (d in KNIGHT) {
                    val nf = f + d[0]
                    val nr = r + d[1]
                    if (nf !in 0..7 || nr !in 0..7) continue
                    val tc = sq[nr * 8 + nf]
                    if (tc == '.' || isWhite(tc) != white) add(from, nr * 8 + nf)
                }
                'b', 'r', 'q' -> {
                    val lc = c.lowercaseChar()
                    for (dr in -1..1) for (df in -1..1) {
                        if (dr == 0 && df == 0) continue
                        val diag = dr != 0 && df != 0
                        if (lc == 'b' && !diag) continue
                        if (lc == 'r' && diag) continue
                        var nf = f + df
                        var nr = r + dr
                        while (nf in 0..7 && nr in 0..7) {
                            val tc = sq[nr * 8 + nf]
                            if (tc == '.') add(from, nr * 8 + nf)
                            else {
                                if (isWhite(tc) != white) add(from, nr * 8 + nf)
                                break
                            }
                            nf += df
                            nr += dr
                        }
                    }
                }
                'k' -> {
                    for (dr in -1..1) for (df in -1..1) {
                        if (dr == 0 && df == 0) continue
                        val nf = f + df
                        val nr = r + dr
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val tc = sq[nr * 8 + nf]
                        if (tc == '.' || isWhite(tc) != white) add(from, nr * 8 + nf)
                    }
                    val home = if (white) 4 else 60
                    if (from == home && !isAttacked(sq, home, !white)) {
                        val rook = if (white) 'R' else 'r'
                        val ks = if (white) 'K' else 'k'
                        val qs = if (white) 'Q' else 'q'
                        if (p.castling.indexOf(ks) >= 0 && sq[home + 3] == rook &&
                            sq[home + 1] == '.' && sq[home + 2] == '.' &&
                            !isAttacked(sq, home + 1, !white) && !isAttacked(sq, home + 2, !white)
                        ) add(from, home + 2)
                        if (p.castling.indexOf(qs) >= 0 && sq[home - 4] == rook &&
                            sq[home - 1] == '.' && sq[home - 2] == '.' && sq[home - 3] == '.' &&
                            !isAttacked(sq, home - 1, !white) && !isAttacked(sq, home - 2, !white)
                        ) add(from, home - 2)
                    }
                }
            }
        }
        return pseudo.filter { m ->
            val np = apply(p, m)
            val k = kingSquare(np.sq, white)
            k >= 0 && !isAttacked(np.sq, k, !white)
        }
    }

    fun apply(p: Position, m: Move): Position {
        val s = p.sq.copyOf()
        val from = m.from
        val to = m.to
        val c = s[from]
        val white = isWhite(c)
        var capture = s[to] != '.'
        val lc = c.lowercaseChar()
        s[to] = c
        s[from] = '.'
        var ep = -1
        if (lc == 'p') {
            if (to == p.ep && !capture && from % 8 != to % 8) {
                s[if (white) to - 8 else to + 8] = '.'
                capture = true
            }
            if (abs(to - from) == 16) ep = (from + to) / 2
            if (to / 8 == 7 || to / 8 == 0) {
                val pr = m.promotion ?: 'q'
                s[to] = if (white) pr.uppercaseChar() else pr.lowercaseChar()
            }
        }
        if (lc == 'k' && abs(to - from) == 2) {
            if (to > from) { s[from + 1] = s[from + 3]; s[from + 3] = '.' }
            else { s[from - 1] = s[from - 4]; s[from - 4] = '.' }
        }
        var cr = p.castling
        fun drop(ch: Char) { cr = cr.replace(ch.toString(), "") }
        if (c == 'K') { drop('K'); drop('Q') }
        if (c == 'k') { drop('k'); drop('q') }
        if (from == 0 || to == 0) drop('Q')
        if (from == 7 || to == 7) drop('K')
        if (from == 56 || to == 56) drop('q')
        if (from == 63 || to == 63) drop('k')
        if (cr.isEmpty()) cr = "-"
        val half = if (lc == 'p' || capture) 0 else p.halfmove + 1
        val full = if (!white) p.fullmove + 1 else p.fullmove
        return Position(s, !p.whiteToMove, cr, ep, half, full)
    }

    /** null إذا كانت الوضعية معقولة؛ وإلا سبب الرفض. لا تُرسل إلى المحرك وضعية غير سليمة. */
    fun validate(p: Position): String? {
        var wk = 0; var bk = 0; var wp = 0; var bp = 0; var w = 0; var b = 0
        for (i in 0..63) {
            val c = p.sq[i]
            if (c == '.') continue
            if (c !in "KQRBNPkqrbnp") return "رمز قطعة غير معروف"
            if (isWhite(c)) w++ else b++
            when (c) {
                'K' -> wk++
                'k' -> bk++
                'P' -> { wp++; if (i / 8 == 0 || i / 8 == 7) return "بيدق على صف غير ممكن" }
                'p' -> { bp++; if (i / 8 == 0 || i / 8 == 7) return "بيدق على صف غير ممكن" }
            }
        }
        if (wk != 1 || bk != 1) return "عدد الملوك غير صحيح"
        if (wp > 8 || bp > 8) return "عدد البيادق كبير"
        if (w > 16 || b > 16) return "عدد القطع كبير"
        if (inCheck(p, !p.whiteToMove)) return "الطرف الذي ليس عليه الدور في حالة كش"
        return null
    }
}
