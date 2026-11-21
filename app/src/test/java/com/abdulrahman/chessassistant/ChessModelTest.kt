package com.abdulrahman.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChessModelTest {
    private fun startBoard(): Board {
        val rows = listOf("rnbqkbnr", "pppppppp", "........", "........", "........", "........", "PPPPPPPP", "RNBQKBNR")
        return Array(8) { rows[it].toCharArray() }
    }

    @Test fun startPositionFen() {
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            Fen.toFen(startBoard(), castling = "KQkq")
        )
    }

    @Test fun emptyBoardFen() {
        assertEquals("8/8/8/8/8/8/8/8 b - - 0 1", Fen.toFen(Fen.emptyBoard(), whiteToMove = false))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidPieceRejected() {
        val b = Fen.emptyBoard(); b[0][0] = 'x'; Fen.toFen(b)
    }

    @Test fun parseUci() {
        assertEquals(Move(4, 1, 4, 3), Move.parseUci("e2e4"))
        assertEquals(Move(4, 6, 4, 7, 'q'), Move.parseUci("e7e8q"))
        assertNull(Move.parseUci("z9z9"))
    }

    @Test fun parseStockfishResponse() {
        val json = """{"success":true,"evaluation":0.3,"mate":null,"bestmove":"bestmove e2e4 ponder e7e5","continuation":"e2e4 e7e5"}"""
        assertEquals(Move(4, 1, 4, 3), StockfishClient.parseBestMove(json))
        assertNull(StockfishClient.parseBestMove("""{"success":false}"""))
    }

    @Test fun geometryCenters() {
        val g = BoardGeometry(0f, 0f, 800f)
        assertEquals(Pair(50f, 750f), g.center(0, 0)) // a1 أسفل اليسار
        assertEquals(Pair(750f, 50f), g.center(7, 7)) // h8 أعلى اليمين
        val f = BoardGeometry(0f, 0f, 800f, flipped = true)
        assertEquals(Pair(750f, 50f), f.center(0, 0)) // a1 أعلى اليمين عند القلب
    }
}
