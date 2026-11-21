package com.abdulrahman.chessassistant

sealed class TrackResult {
    object NoChange : TrackResult()
    data class Moved(val moves: List<Move>) : TrackResult()
    object Mismatch : TrackResult()
}

/**
 * يتتبع الوضعية: يقارن ما رُصد على الشاشة (obs: 64 خانة، فهرس rank*8+file) مع النقلات القانونية
 * من الوضعية الحالية. لا يقبل أي تغيير بصري ما لم يطابق نقلة قانونية (أو نقلتين إذا فاتت لقطة).
 */
class MoveTracker {
    var position: Position = Rules.startPosition()
        private set

    /** true إذا كان الدور مخمّناً (بعد إعادة مزامنة)، فيُجرَّب الطرفان. */
    var turnGuessed: Boolean = false
        private set

    fun reset(p: Position, guessed: Boolean = false) {
        position = p
        turnGuessed = guessed
    }

    // Compare exact piece identities, not only occupied/white/black classes.
    // This is essential for promotions and for rejecting a visually misclassified piece type.
    private fun sameClasses(a: CharArray, b: CharArray): Boolean = a.contentEquals(b)

    /** يختار من المرشحات؛ الترقيات تختلف فقط بنوع القطعة فنفضّل المطابق لما رُصد. */
    private fun pick(cands: List<Move>, obs: CharArray): Move? {
        if (cands.isEmpty()) return null
        val pairs = cands.map { it.from * 64 + it.to }.toSet()
        if (pairs.size > 1) return null
        if (cands.size == 1) return cands[0]
        val seen = obs[cands[0].to].lowercaseChar()
        return cands.firstOrNull { it.promotion == seen } ?: cands.firstOrNull { it.promotion == 'q' } ?: cands[0]
    }

    fun update(obs: CharArray): TrackResult {
        if (sameClasses(position.sq, obs)) return TrackResult.NoChange
        val bases = if (turnGuessed) listOf(position.withTurn(true), position.withTurn(false)) else listOf(position)

        // نقلة واحدة
        for (base in bases) {
            val cands = ArrayList<Move>()
            for (m in Rules.legalMoves(base)) {
                val np = Rules.apply(base, m)
                if (np.sq.contentEquals(obs)) {
                    commit(Rules.apply(base, m)); return TrackResult.Moved(listOf(m))
                }
                if (sameClasses(np.sq, obs)) cands.add(m)
            }
            val m = pick(cands, obs)
            if (m != null) {
                commit(Rules.apply(base, m)); return TrackResult.Moved(listOf(m))
            }
        }
        // نقلتان (فاتتنا لقطة)
        for (base in bases) {
            val found = ArrayList<Pair<Move, Move>>()
            for (m1 in Rules.legalMoves(base)) {
                val p1 = Rules.apply(base, m1)
                for (m2 in Rules.legalMoves(p1)) {
                    val p2 = Rules.apply(p1, m2)
                    if (sameClasses(p2.sq, obs)) found.add(Pair(m1, m2))
                }
            }
            val keys = found.map { (it.first.from * 64 + it.first.to) * 4096 + it.second.from * 64 + it.second.to }.toSet()
            if (keys.size == 1) {
                val (a, b) = found.firstOrNull { Rules.apply(Rules.apply(base, it.first), it.second).sq.contentEquals(obs) }
                    ?: found.first()
                val p1 = Rules.apply(base, a)
                commit(Rules.apply(p1, b))
                return TrackResult.Moved(listOf(a, b))
            }
        }
        return TrackResult.Mismatch
    }

    private fun commit(p: Position) {
        position = p
        turnGuessed = false
    }
}
