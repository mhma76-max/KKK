package com.abdulrahman.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        for (m in Rules.legalMoves(p)) n += perft(Rules.apply(p, m), depth - 1)
        return n
    }

    private fun perft(fen: String, depth: Int) = perft(Rules.fromFen(fen)!!, depth)

    @Test fun startPerft() {
        assertEquals(20L, perft(Rules.START_FEN, 1))
        assertEquals(400L, perft(Rules.START_FEN, 2))
        assertEquals(8902L, perft(Rules.START_FEN, 3))
    }

    @Test fun kiwipetePerft() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(48L, perft(fen, 1))
        assertEquals(2039L, perft(fen, 2))
    }

    @Test fun position3Perft() {
        val fen = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
        assertEquals(14L, perft(fen, 1))
        assertEquals(191L, perft(fen, 2))
        assertEquals(2812L, perft(fen, 3))
    }

    @Test fun position4PerftPromotionsAndCastling() {
        val fen = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"
        assertEquals(6L, perft(fen, 1))
        assertEquals(264L, perft(fen, 2))
    }

    @Test fun fenRoundTrip() {
        assertEquals(Rules.START_FEN, Rules.toFen(Rules.startPosition()))
        val p = Rules.apply(Rules.startPosition(), Move.parseUci("e2e4")!!)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", Rules.toFen(p))
    }

    @Test fun castlingMovesRook() {
        val p = Rules.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")!!
        val n = Rules.apply(p, Move.parseUci("e1g1")!!)
        assertEquals('K', n.sq[6]); assertEquals('R', n.sq[5]); assertEquals('.', n.sq[7])
        assertEquals("kq", n.castling)
    }

    @Test fun enPassantRemovesPawn() {
        val p = Rules.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")!!
        assertTrue(Rules.legalMoves(p).any { it.uci() == "e5d6" })
        val n = Rules.apply(p, Move.parseUci("e5d6")!!)
        assertEquals('.', n.sq[3 * 8 + 3])
    }

    @Test fun promotionBecomesPiece() {
        val p = Rules.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1")!!
        val n = Rules.apply(p, Move.parseUci("a7a8n")!!)
        assertEquals('N', n.sq[56])
    }

    @Test fun validateRejectsBadPositions() {
        assertNull(Rules.validate(Rules.startPosition()))
        assertNotNull(Rules.validate(Rules.fromFen("8/8/8/8/8/8/8/K7 w - - 0 1")!!)) // لا ملك أسود
        assertNotNull(Rules.validate(Rules.fromFen("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1")!!)) // الأسود في كش وليس دوره
    }

    @Test fun validateRejectsUnknownPieceSymbols() {
        val bad = Rules.startPosition().sq.copyOf()
        bad[0] = 'X'
        val position = Position(bad, true, "KQkq", -1, 0, 1)
        assertEquals("رمز قطعة غير معروف", Rules.validate(position))
    }
}
