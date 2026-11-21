package com.abdulrahman.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VisionTest {
    private val W = 720
    private val H = 1600
    private val LEFT = 20
    private val TOP = 400
    private val CELL = 85

    private fun frame(fen: String, flipped: Boolean = false, dots: Set<Int> = emptySet()) =
        SyntheticBoard.render(W, H, LEFT, TOP, CELL, SyntheticBoard.fromFen(fen), flipped, dots)

    private fun screenIndex(row: Int, col: Int, flip: Boolean) =
        if (!flip) (7 - row) * 8 + col else row * 8 + (7 - col)

    private fun calibrated(f: Frame, rect: BoardRect, flip: Boolean = false): PieceRecognizer {
        val rec = PieceRecognizer()
        val start = Rules.startPosition()
        val list = ArrayList<Pair<Char, SquareSample>>()
        for (i in 0..63) list.add(Pair(start.sq[screenIndex(i / 8, i % 8, flip)], rec.analyze(f, rect, i % 8, i / 8)))
        assertTrue(rec.calibrate(list))
        return rec
    }

    @Test fun detectsBoardRect() {
        val r = BoardDetector().detect(frame(Rules.START_FEN))
        assertNotNull(r)
        assertTrue("left ${r!!.left}", abs(r.left - LEFT) <= 8)
        assertTrue("top ${r.top}", abs(r.top - TOP) <= 8)
        assertTrue("size ${r.size}", abs(r.size - 8 * CELL) <= 12)
    }

    @Test fun detectsOtherThemesWithoutConfiguration() {
        val themes = listOf(
            Pair(BoardTheme.rgb(240, 217, 181), BoardTheme.rgb(181, 136, 99)),   // بني
            Pair(BoardTheme.rgb(222, 227, 230), BoardTheme.rgb(140, 162, 173)),  // أزرق رمادي
            Pair(BoardTheme.rgb(234, 233, 210), BoardTheme.rgb(75, 115, 153))    // أزرق
        )
        for ((light, dark) in themes) {
            val f = SyntheticBoard.render(W, H, LEFT, TOP, CELL, SyntheticBoard.fromFen(Rules.START_FEN), false, emptySet(), light, dark)
            val r = BoardDetector().detect(f)
            assertNotNull("theme $light/$dark", r)
            assertTrue(abs(r!!.left - LEFT) <= 8 && abs(r.top - TOP) <= 8 && abs(r.size - 8 * CELL) <= 12)
        }
    }

    @Test fun noBoardInBlankFrame() {
        val blank = Frame(IntArray(W * H) { BoardTheme.rgb(49, 46, 43) }, W, H)
        assertEquals(null, BoardDetector().detect(blank))
    }

    @Test fun recognizesStartPositionAfterCalibration() {
        val f = frame(Rules.START_FEN)
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(f, rect)
        val start = Rules.startPosition()
        for (row in 0..7) for (col in 0..7) {
            val sample = rec.analyze(f, rect, col, row)
            val expected = start.sq[screenIndex(row, col, false)]
            val got = rec.classifyConfident(sample)
            assertEquals("row=$row col=$col", expected, got)
        }
    }

    @Test fun recognizesOtherPositionsAndEmptySquares() {
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(frame(Rules.START_FEN), rect)
        // قطع على مربعات ألوانها مختلفة عن الابتدائية + مربعات فارغة
        val fen = "4k3/3q4/2b5/8/3N4/5R2/1p6/4K3 w - - 0 1"
        val f = frame(fen)
        val expected = Rules.fromFen(fen)!!.sq
        for (row in 0..7) for (col in 0..7) {
            val got = rec.classify(rec.analyze(f, rect, col, row))
            assertEquals("row=$row col=$col", expected[screenIndex(row, col, false)], got)
        }
    }

    @Test fun legalMoveDotsDoNotCountAsPieces() {
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(frame(Rules.START_FEN), rect)
        val f = frame(Rules.START_FEN, dots = setOf(2 * 8 + 4, 3 * 8 + 4)) // e3, e4
        assertEquals('.', rec.classify(rec.analyze(f, rect, 4, 5)))
        assertEquals('.', rec.classify(rec.analyze(f, rect, 4, 4)))
    }

    @Test fun exportImportRoundTrip() {
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(frame(Rules.START_FEN), rect)
        val other = PieceRecognizer()
        assertTrue(other.import(rec.export()))
        val f = frame("4k3/8/8/8/3N4/8/8/4K3 w - - 0 1")
        assertEquals('N', other.classify(other.analyze(f, rect, 3, 4)))
    }

    @Test fun strictRecognitionRejectsUnseenSilhouetteInsteadOfGuessing() {
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(frame(Rules.START_FEN), rect)
        val alien = SquareSample(FloatArray(PieceRecognizer.T * PieceRecognizer.T) { 1f }, 0.65f, 220f)
        assertEquals(null, rec.classifyConfident(alien))
    }

    @Test fun calibratedExemplarsSurviveExportImport() {
        val rect = BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL)
        val rec = calibrated(frame(Rules.START_FEN), rect)
        val other = PieceRecognizer()
        assertTrue(other.import(rec.export()))
        val f = frame("4k3/8/8/8/3N4/8/8/4K3 w - - 0 1")
        assertEquals('N', other.classifyConfident(other.analyze(f, rect, 3, 4)))
    }

    // ---- الدورة الكاملة بمخرجات وهمية ----

    private class FakeOutput : CoreOutput {
        val requests = ArrayList<Pair<String, Long>>()
        var arrow: Move? = null
        var hidden = 0
        override fun showArrow(rect: BoardRect, flipped: Boolean, move: Move) { arrow = move }
        override fun hideArrow() { arrow = null; hidden++ }
        override fun requestAnalysis(fen: String, token: Long) { requests.add(Pair(fen, token)) }
        override fun status(text: String) {}
    }

    private fun feed(core: AssistantCore, clock: LongArray, f: Frame, times: Int) {
        repeat(times) { core.onFrame(f); clock[0] += 250 }
    }

    @Test fun fullLoopFromStartThroughTwoMoves() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        val start = frame(Rules.START_FEN)
        feed(core, clock, start, 3)                       // اكتشاف مرتين + معايرة
        assertTrue(core.isTracking)
        assertEquals(1, out.requests.size)
        assertEquals(Rules.START_FEN, out.requests[0].first)

        core.onEngineResult(out.requests[0].second, out.requests[0].first, Move.parseUci("e2e4"), null)
        assertEquals("e2e4", out.arrow?.uci())
        feed(core, clock, start, 3)                       // إعادة خط الأساس

        // المستخدم يلعب e4: الدور ينتقل للخصم؛ لا طلب للمحرك
        val afterE4 = frame("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        feed(core, clock, afterE4, 6)
        assertEquals(1, out.requests.size)
        assertEquals(null, out.arrow)

        // ردّ الخصم e5 ← طلب تحليل جديد بدور الأبيض
        val afterE5 = frame("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2")
        feed(core, clock, afterE5, 6)
        assertEquals(2, out.requests.size)
        assertTrue(out.requests[1].first.startsWith("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq"))
    }

    @Test fun staleOrIllegalEngineAnswerIgnored() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        feed(core, clock, frame(Rules.START_FEN), 3)
        core.onEngineResult(out.requests[0].second, out.requests[0].first, Move.parseUci("e2e5"), null) // غير قانونية
        assertEquals(null, out.arrow)
        core.onEngineResult(999, out.requests[0].first, Move.parseUci("e2e4"), null)                   // رمز قديم
        assertEquals(null, out.arrow)
    }

    @Test fun blackAtBottomOrientationAndArrowGeometry() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        feed(core, clock, frame(Rules.START_FEN, flipped = true), 3)
        assertTrue(core.isTracking)
        // المستخدم أسود: الدور للأبيض، فلا طلب للمحرك
        assertEquals(0, out.requests.size)
        val g = BoardGeometry(LEFT.toFloat(), TOP.toFloat(), 8f * CELL, flipped = true)
        val c = g.center(4, 1) // e2 عند القلب: العمود 3 والصف 1
        assertEquals(LEFT + 3.5f * CELL, c.first, 0.01f)
        assertEquals(TOP + 1.5f * CELL, c.second, 0.01f)
    }

    // ---- المباراة الثانية، نهاية المباراة، الإعادة، التحديد اليدوي ----

    @Test fun newGameIsDetectedAndTrackingRestarts() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        val start = frame(Rules.START_FEN)
        feed(core, clock, start, 3)
        feed(core, clock, frame("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"), 6)
        assertEquals(1, out.requests.size)
        feed(core, clock, start, 6)                       // مباراة جديدة
        assertEquals(2, out.requests.size)
        assertEquals(Rules.START_FEN, out.requests[1].first)
    }

    @Test fun checkmateEndsGameThenNewGameStarts() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        feed(core, clock, frame(Rules.START_FEN), 3)
        feed(core, clock, frame("rnbqkbnr/pppppppp/8/8/8/5P2/PPPPP1PP/RNBQKBNR b KQkq - 0 1"), 6)
        feed(core, clock, frame("rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq e6 0 2"), 6)
        feed(core, clock, frame("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"), 6)
        feed(core, clock, frame("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"), 6)
        assertTrue(core.isGameOver)
        val n = out.requests.size
        feed(core, clock, frame(Rules.START_FEN), 6)      // مباراة جديدة بعد كش مات
        assertTrue(!core.isGameOver)
        assertEquals(n + 1, out.requests.size)
    }

    @Test fun engineFailureIsRetried() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        val start = frame(Rules.START_FEN)
        feed(core, clock, start, 3)
        core.onEngineResult(out.requests[0].second, out.requests[0].first, null, "خطأ شبكة")
        feed(core, clock, start, 12)
        assertTrue(out.requests.size >= 2)
        assertTrue(out.requests[1].second > out.requests[0].second)
    }

    @Test fun manualRectSkipsAutoDetection() {
        val clock = longArrayOf(0)
        val out = FakeOutput()
        val core = AssistantCore(out, clock = { clock[0] })
        core.setManualRect(BoardRect(LEFT.toFloat(), TOP.toFloat(), 8f * CELL))
        feed(core, clock, frame(Rules.START_FEN), 1)
        assertTrue(core.isTracking)
    }
}
