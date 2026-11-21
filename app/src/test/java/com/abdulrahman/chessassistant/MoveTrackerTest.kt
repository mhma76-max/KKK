package com.abdulrahman.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTrackerTest {
    private fun play(t: MoveTracker, uci: String): TrackResult {
        val m = Move.parseUci(uci)!!
        return t.update(Rules.apply(t.position, m).sq)
    }

    @Test fun normalMovesAndCapture() {
        val t = MoveTracker()
        for (u in listOf("e2e4", "d7d5", "e4d5", "d8d5")) {
            val r = play(t, u)
            assertTrue("$u -> $r", r is TrackResult.Moved)
        }
        assertEquals("rnb1kbnr/ppp1pppp/8/3q4/8/8/PPPP1PPP/RNBQKBNR w KQkq - 0 3", Rules.toFen(t.position))
    }

    @Test fun noChange() {
        val t = MoveTracker()
        assertEquals(TrackResult.NoChange, t.update(Rules.startPosition().sq.copyOf()))
    }

    @Test fun castlingDetected() {
        val t = MoveTracker()
        t.reset(Rules.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")!!)
        val r = play(t, "e1c1")
        assertTrue(r is TrackResult.Moved)
        assertEquals('K', t.position.sq[2]); assertEquals('R', t.position.sq[3])
    }

    @Test fun enPassantDetected() {
        val t = MoveTracker()
        t.reset(Rules.fromFen("4k3/3p4/8/4P3/8/8/8/4K3 b - - 0 1")!!)
        assertTrue(play(t, "d7d5") is TrackResult.Moved)
        assertTrue(play(t, "e5d6") is TrackResult.Moved)
        assertEquals('.', t.position.sq[4 * 8 + 3])
    }

    @Test fun promotionPicksObservedPiece() {
        val t = MoveTracker()
        t.reset(Rules.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1")!!)
        val obs = Rules.apply(t.position, Move.parseUci("a7a8n")!!).sq
        val r = t.update(obs)
        assertTrue(r is TrackResult.Moved)
        assertEquals('N', t.position.sq[56])
    }

    @Test fun missedFrameTwoPly() {
        val t = MoveTracker()
        val p1 = Rules.apply(Rules.startPosition(), Move.parseUci("e2e4")!!)
        val p2 = Rules.apply(p1, Move.parseUci("e7e5")!!)
        val r = t.update(p2.sq)
        assertTrue(r is TrackResult.Moved)
        assertEquals(2, (r as TrackResult.Moved).moves.size)
    }

    @Test fun sameColorPieceTypeChangeIsNotIgnored() {
        val t = MoveTracker()
        val altered = Rules.startPosition().sq.copyOf()
        altered[1] = 'B' // knight changed into a bishop without a legal move
        assertEquals(TrackResult.Mismatch, t.update(altered))
        assertEquals(Rules.START_FEN, Rules.toFen(t.position))
    }

    @Test fun illegalChangeIsMismatch() {
        val t = MoveTracker()
        val bad = Rules.startPosition().sq.copyOf()
        bad[8] = '.'; bad[40] = 'P'   // a2 -> a6 (غير قانوني)
        assertEquals(TrackResult.Mismatch, t.update(bad))
        assertEquals(Rules.START_FEN, Rules.toFen(t.position)) // لم تتغير الوضعية
    }

    @Test fun guessedTurnTriesBothSides() {
        val t = MoveTracker()
        val p = Rules.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1")!! // الدور مخمّن خطأً
        t.reset(p, guessed = true)
        val r = t.update(Rules.apply(p.withTurn(false), Move.parseUci("e7e5")!!).sq)
        assertTrue(r is TrackResult.Moved)
    }
}
