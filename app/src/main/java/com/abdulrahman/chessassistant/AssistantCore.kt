package com.abdulrahman.chessassistant

import kotlin.math.abs

/** مخرجات الدورة (تُنفَّذ في الخدمة، وتُستبدل بنسخة وهمية في الاختبارات). */
interface CoreOutput {
    fun showArrow(rect: BoardRect, flipped: Boolean, move: Move)
    fun hideArrow()
    fun requestAnalysis(fen: String, token: Long)
    fun status(text: String)
    /** لم تُكتشف الرقعة تلقائياً: اطلب من المستخدم تحديدها يدوياً. */
    /** نص تشخيصي (الوضعية المتتبَّعة مقابل المرصودة) يُعرض في الإشعار الموسّع. */
    fun debug(text: String) {}
    fun requestBoardSelection() {}
    fun cancelBoardSelection() {}
    /** الموضع اليدوي المحفوظ لم يعمل؛ احذفه. */
    fun manualRectRejected() {}
}

/**
 * الدورة الكاملة: إطار ← رقعة (يدوية أو تلقائية) ← معايرة/تعرّف ← تتبع نقلات ← تحقق ← FEN ← تحليل ← سهم.
 * يستمر طوال المباراة حتى نهايتها (كش مات/جمود)، ثم ينتظر مباراة جديدة ويكتشفها تلقائياً (وضعية بداية جديدة).
 * كل الدوال تُستدعى من خيط واحد. نتيجة المحرك تعود عبر onEngineResult على الخيط نفسه.
 * عند أي تغيير في الرقعة يُخفى السهم أولاً (حتى لا يظهر في لقطة التحليل)، ثم يُحلَّل الإطار بعد استقراره.
 */
class AssistantCore(
    private val output: CoreOutput,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val detector: BoardDetector = BoardDetector(),
    val recognizer: PieceRecognizer = PieceRecognizer(),
    private val tracker: MoveTracker = MoveTracker(),
    private val settleMs: Long = 220,
    private val rebaselineMs: Long = 300
) {
    private enum class State { SEARCH, TRACK }
    private enum class Phase { IDLE, PENDING }

    private val startPlacement = Rules.START_FEN.substringBefore(' ')

    private var state = State.SEARCH
    private var phase = Phase.IDLE
    private var rect: BoardRect? = null
    private var manualRect: BoardRect? = null
    private var candidateRect: BoardRect? = null
    private var flipped = false
    private var userWhite = true

    private var searchSince = 0L
    private var selectionRequested = false

    private var baseline: IntArray? = null
    private var baselineAt = 0L
    private var lastFp: IntArray? = null
    private var stableCount = 0
    private var pendingSince = 0L
    private var changeSince = 0L
    private var mismatchCount = 0

    private var token = 0L
    private var lastMove: Move? = null
    private val cache = HashMap<String, Move>()
    private var retries = 0
    private var retryAt = 0L
    private var lastStatus = ""

    var isGameOver = false
        private set
    val position: Position get() = tracker.position
    val isTracking: Boolean get() = state == State.TRACK

    private fun say(s: String) { if (s != lastStatus) { lastStatus = s; output.status(s) } }

    /** موضع الرقعة المحدد يدوياً (بإحداثيات الشاشة). يُستخدم بدل الاكتشاف التلقائي. */
    fun setManualRect(r: BoardRect) {
        manualRect = r
        selectionRequested = false
        candidateRect = null
        searchSince = clock()
        if (state == State.TRACK) { state = State.SEARCH; rect = null; output.hideArrow() }
        say("تم تحديد الرقعة يدوياً")
    }

    fun onFrame(frame: Frame) {
        when (state) {
            State.SEARCH -> search(frame)
            State.TRACK -> track(frame)
        }
    }

    // ---------- الإعداد ----------

    private fun search(frame: Frame) {
        val now = clock()
        if (searchSince == 0L) searchSince = now
        var manual = manualRect
        if (manual != null && now - searchSince > 8000) {      // الموضع اليدوي لم ينجح: ارجع للاكتشاف التلقائي
            manualRect = null; manual = null
            output.manualRectRejected()
            searchSince = now
        }
        val r = manual ?: detector.detect(frame)
        if (r == null) {
            candidateRect = null
            say("لم أجد الرقعة")
            if (!selectionRequested && now - searchSince > 2000) {
                selectionRequested = true
                output.requestBoardSelection()
                say("حدّد الرقعة بإصبعك على الشاشة")
            }
            return
        }
        if (manual == null) {
            val c = candidateRect
            if (c == null || abs(c.left - r.left) > r.cell * 0.15f || abs(c.top - r.top) > r.cell * 0.15f ||
                abs(c.size - r.size) > r.size * 0.02f
            ) { candidateRect = r; return }
            if (selectionRequested) { selectionRequested = false; output.cancelBoardSelection() }
        }

        val samples = Array(64) { recognizer.analyze(frame, r, it % 8, it / 8) }   // فهرس = row*8+col (شاشة)
        val occ = BooleanArray(64) { samples[it].occupancy >= PieceRecognizer.EMPTY_THRESHOLD }
        if (looksLikeStart(occ)) {
            if (setupFromStart(frame, r, samples)) analyzeCurrent()
        } else if (recognizer.isCalibrated) {
            val obs0 = recognizeFrom(samples, false)
            if (obs0.any { it == '?' }) { say("هناك قطع غير واضحة؛ لا أستطيع تحديد الوضعية بأمان"); return }
            // بالفهرس غير المقلوب: 0..31 = النصف السفلي من الشاشة
            val whitesBottom = (0..31).count { Rules.isWhite(obs0[it]) }
            val whitesTop = (32..63).count { Rules.isWhite(obs0[it]) }
            val wb = whitesBottom >= whitesTop
            rect = r; flipped = !wb; userWhite = wb
            if (!resync(recognizeFrom(samples, !wb))) { rect = null; say("الوضعية غير موثوقة"); return }
            enterTrack(frame, "تمت المزامنة من منتصف المباراة")
            analyzeCurrent()
        } else {
            say("ابدأ من الوضعية الابتدائية للمعايرة")
        }
    }

    /** يعاير القوالب ويبدأ التتبع من وضعية البداية (مباراة جديدة أو أولى). */
    private fun setupFromStart(frame: Frame, r: BoardRect, samples: Array<SquareSample>): Boolean {
        val whiteBottom = isWhiteAtBottom(samples)
        if (whiteBottom == null) { say("تعذّر تحديد اتجاه الرقعة"); return false }
        val start = Rules.startPosition()
        val list = ArrayList<Pair<Char, SquareSample>>()
        for (i in 0..63) list.add(Pair(start.sq[screenToIndex(i / 8, i % 8, !whiteBottom)], samples[i]))
        if (!recognizer.calibrate(list)) { say("فشلت المعايرة"); return false }
        // Calibration is accepted only if every occupied start square is recognized confidently
        // as the expected piece. A partial/ambiguous calibration must never reach the engine.
        for (screen in 0 until 64) {
            val expected = start.sq[screenToIndex(screen / 8, screen % 8, !whiteBottom)]
            val actual = recognizer.classifyConfident(samples[screen])
            if (actual == null || actual != expected) {
                say("المعايرة غير مؤكدة عند مربع ${screen % 8 + 1},${screen / 8 + 1}؛ أعد التقاط الرقعة")
                return false
            }
        }
        rect = r; flipped = !whiteBottom; userWhite = whiteBottom
        tracker.reset(start)
        isGameOver = false
        cache.clear(); lastMove = null
        retries = 0; retryAt = 0
        enterTrack(frame, if (whiteBottom) "جاهز: أنت الأبيض" else "جاهز: أنت الأسود")
        return true
    }

    private fun isWhiteAtBottom(samples: Array<SquareSample>): Boolean? {
        val top = (0..15).map { samples[it].luminance }.average()
        val bottom = (48..63).map { samples[it].luminance }.average()
        if (abs(top - bottom) < 40.0) return null
        return bottom > top
    }

    private fun enterTrack(frame: Frame, msg: String) {
        state = State.TRACK
        phase = Phase.IDLE
        baseline = fingerprint(frame, rect!!)
        baselineAt = 0
        mismatchCount = 0
        searchSince = 0
        selectionRequested = false
        say(msg)
    }

    private fun looksLikeStart(occ: BooleanArray): Boolean {
        for (row in 0..7) for (col in 0..7) {
            val shouldBe = row <= 1 || row >= 6
            if (occ[row * 8 + col] != shouldBe) return false
        }
        return true
    }

    /** فهرس مربع الشاشة (row,col) إلى فهرس الشطرنج rank*8+file. */
    private fun screenToIndex(row: Int, col: Int, flip: Boolean): Int =
        if (!flip) (7 - row) * 8 + col else row * 8 + (7 - col)

    private fun recognizeFrom(samples: Array<SquareSample>, flip: Boolean): CharArray {
        val obs = CharArray(64)
        for (row in 0..7) for (col in 0..7) {
            obs[screenToIndex(row, col, flip)] = recognizer.classifyConfident(samples[row * 8 + col]) ?: '?'
        }
        return obs
    }

    // ---------- التتبع ----------

    private fun track(frame: Frame) {
        val r = rect ?: return
        val now = clock()
        val fp = fingerprint(frame, r)
        if (baselineAt > 0) {
            if (now < baselineAt) return
            baseline = fp; baselineAt = 0
            return
        }
        if (phase == Phase.IDLE && retryAt > 0 && now >= retryAt) {   // إعادة محاولة بعد فشل المحرك/الشبكة
            retryAt = 0
            analyzeCurrent()
            return
        }
        when (phase) {
            Phase.IDLE -> {
                if (changed(fp, baseline!!) >= 8) {
                    output.hideArrow()
                    phase = Phase.PENDING
                    pendingSince = now
                    changeSince = now
                    lastFp = fp
                    stableCount = 0
                    mismatchCount = 0
                    retryAt = 0
                    say("تغيّرت الرقعة…")
                }
            }
            Phase.PENDING -> {
                if (now - pendingSince < settleMs) return
                val prev = lastFp
                if (prev != null && changed(fp, prev) <= 6) stableCount++ else stableCount = 0
                lastFp = fp
                // لا ننتظر الاستقرار الكامل أكثر من 2.5 ثانية (حركة مستمرة أو ضجيج في الصورة)
                if (stableCount >= 2 || now - changeSince > 2500) process(frame, r, fp, now)
            }
        }
    }

    private fun process(frame: Frame, r: BoardRect, fp: IntArray, now: Long) {
        val samples = Array(64) { recognizer.analyze(frame, r, it % 8, it / 8) }
        val occ = BooleanArray(64) { samples[it].occupancy >= PieceRecognizer.EMPTY_THRESHOLD }

        // مباراة جديدة: ظهرت وضعية البداية (أو تغيّر لون اللاعب) بينما التتبع على وضعية أخرى
        if (looksLikeStart(occ)) {
            val wb = isWhiteAtBottom(samples)
            val atStart = Rules.toFen(tracker.position).substringBefore(' ') == startPlacement
            if (wb != null && (!atStart || (!wb) != flipped)) {
                if (setupFromStart(frame, r, samples)) { say("مباراة جديدة"); analyzeCurrent(); return }
            }
        }
        if (isGameOver) { finish(fp); return }          // بعد النهاية: لا نتتبع إلا مباراة جديدة

        val obs = recognizeFrom(samples, flipped)
        if (obs.any { it == '?' }) {
            // Keep the last trusted position. Do not resync from a board containing guessed pieces.
            output.hideArrow()
            phase = Phase.PENDING
            pendingSince = now
            changeSince = now
            lastFp = fp
            stableCount = 0
            output.debug(describeUnknowns(samples, obs, r))
            say("تعذّر تأكيد بعض القطع؛ أعيد الفحص دون إرسال وضعية للمحرك")
            return
        }
        when (val res = tracker.update(obs)) {
            is TrackResult.NoChange -> { finish(fp); showCachedOrAnalyze() }
            is TrackResult.Moved -> {
                retries = 0; retryAt = 0
                finish(fp)
                say("نقلة: " + res.moves.joinToString(" ") { it.uci() })
                analyzeCurrent()
            }
            is TrackResult.Mismatch -> {
                mismatchCount++
                if (mismatchCount < 2) {          // محاولة واحدة إضافية (قد تكون نقاط النقلات المتاحة أو حركة سحب)
                    stableCount = 0; pendingSince = now; lastFp = fp
                    say("تغيّر غير مطابق لنقلة قانونية، أعيد الفحص…")
                    output.debug(describe("مراقَب", obs) + "\n" + "متتبَّع: " + ltr(Rules.toFen(tracker.position)))
                } else {
                    finish(fp)
                    val ok = resync(obs, tracker.position)
                    output.debug(describe("مراقَب", obs) + "\nمتتبَّع سابقاً: " + ltr(Rules.toFen(tracker.position)) + "\nمزامنة: " + ok)
                    if (ok) { say("أُعيدت المزامنة من الرقعة"); analyzeCurrent() }
                    else say("الوضعية المرصودة غير سليمة؛ لن أرسل شيئاً للمحرك")
                }
            }
        }
    }


    /** تشخيص ميداني للمربعات المرفوضة دون تغيير قرار التعرف أو تخمين قطعة. */
    private fun describeUnknowns(samples: Array<SquareSample>, obs: CharArray, r: BoardRect): String {
        val sb = StringBuilder("تشخيص التعرف | رقعة: x=")
            .append(r.left.toInt()).append(" y=").append(r.top.toInt())
            .append(" size=").append(r.size.toInt())
            .append(" | الاتجاه=").append(if (flipped) "مقلوب" else "عادي")
            .append("\nالمربعات المرفوضة:")
        var count = 0
        for (row in 0..7) for (col in 0..7) {
            val index = screenToIndex(row, col, flipped)
            if (obs[index] != '?') continue
            count++
            if (count > 12) continue
            val sample = samples[row * 8 + col]
            val result = recognizer.classifyDetailed(sample)
            val square = "${'a' + (index % 8)}${index / 8 + 1}"
            sb.append("\n").append(square)
                .append(" occ=").append(String.format(java.util.Locale.US, "%.2f", sample.occupancy))
                .append(" lum=").append(sample.luminance.toInt())
            if (result == null) sb.append(" match=none")
            else sb.append(" best=").append(result.piece)
                .append(" dist=").append(String.format(java.util.Locale.US, "%.3f", result.distance))
                .append(" conf=").append(String.format(java.util.Locale.US, "%.2f", result.confidence))
                .append(" [").append(recognizer.distanceProfile(sample)).append("]")
        }
        sb.append("\nإجمالي المربعات المرفوضة: ").append(count)
        return sb.toString()
    }

    /** عزل النصوص اللاتينية عن اتجاه RTL حتى لا يعيد Android ترتيب رموز الرقعة بصرياً. */
    private fun ltr(value: String): String = "\u2066" + value + "\u2069"

    private fun describe(title: String, obs: CharArray): String {
        val sb = StringBuilder(title).append(":\n")
        for (rank in 7 downTo 0) {
            val row = buildString {
                append(rank + 1).append("  ")
                for (file in 0..7) append(obs[rank * 8 + file]).append(' ')
            }.trimEnd()
            sb.append(ltr(row)).append('\n')
        }
        sb.append(ltr("   a b c d e f g h"))
        sb.append("\nFEN: ").append(ltr(Rules.toFen(Position(obs.copyOf(), tracker.position.whiteToMove, "-", -1, 0, 1))))
        return sb.toString()
    }

    /** الجهة التي تحرّكت آخراً: من وصلت قطعها لمربعات تغيّرت عن الوضعية السابقة. null = غير واضح. */
    private fun inferSideToMove(prev: Position, obs: CharArray): Boolean? {
        var whiteArrived = false
        var blackArrived = false
        for (i in 0..63) {
            val o = obs[i]
            if (o == '.' || o == prev.sq[i]) continue
            if (Rules.isWhite(o)) whiteArrived = true else blackArrived = true
        }
        return when {
            whiteArrived && !blackArrived -> false   // تحرّك الأبيض فالدور للأسود
            blackArrived && !whiteArrived -> true
            else -> null
        }
    }

    private fun finish(fp: IntArray) {
        baseline = fp
        phase = Phase.IDLE
        mismatchCount = 0
    }

    /** بناء وضعية من التعرّف الكامل عند فقدان التزامن (التعرّف على القطع دقيق؛ الدور وحقوق التبييت تخمين محافظ). */
    private fun resync(obs: CharArray, prev: Position? = null): Boolean {
        if (obs.size != 64 || obs.any { it == '?' || (it != '.' && it.lowercaseChar() !in "pnbrqk") }) return false
        var castling = ""
        if (obs[4] == 'K' && obs[7] == 'R') castling += "K"
        if (obs[4] == 'K' && obs[0] == 'R') castling += "Q"
        if (obs[60] == 'k' && obs[63] == 'r') castling += "k"
        if (obs[60] == 'k' && obs[56] == 'r') castling += "q"
        if (castling.isEmpty()) castling = "-"
        val inferred = if (prev != null) inferSideToMove(prev, obs) else null
        val first = inferred ?: userWhite
        var p = Position(obs.copyOf(), first, castling, -1, 0, 1)
        if (Rules.validate(p) != null) {
            p = Position(obs.copyOf(), !first, castling, -1, 0, 1)
            if (Rules.validate(p) != null) return false
        }
        if (inferred == null) {
            val wCheck = Rules.inCheck(p, true)
            val bCheck = Rules.inCheck(p, false)
            if (wCheck != bCheck) p = p.withTurn(wCheck)
        }
        tracker.reset(p, guessed = inferred == null)
        return true
    }

    // ---------- المحرك والسهم ----------

    private fun analyzeCurrent() {
        val r = rect ?: return
        val p = tracker.position
        retryAt = 0
        if (Rules.validate(p) != null) { output.hideArrow(); say("الوضعية غير موثوقة"); return }
        if (Rules.legalMoves(p).isEmpty()) {
            isGameOver = true
            output.hideArrow()
            say(if (Rules.inCheck(p, p.whiteToMove)) "انتهت المباراة: كش مات. بانتظار مباراة جديدة" else "انتهت المباراة: تعادل (جمود). بانتظار مباراة جديدة")
            return
        }
        if (p.whiteToMove != userWhite) { output.hideArrow(); say("دور الخصم"); return }
        val fen = Rules.toFen(p)
        val cached = cache[fen]
        if (cached != null) { lastMove = cached; show(r, cached); return }
        token++
        lastMove = null
        say("جارٍ التحليل…")
        output.requestAnalysis(fen, token)
    }

    private fun showCachedOrAnalyze() {
        val r = rect ?: return
        val m = lastMove
        if (m != null && tracker.position.whiteToMove == userWhite) show(r, m) else analyzeCurrent()
    }

    private fun show(r: BoardRect, m: Move) {
        output.showArrow(r, flipped, m)
        baselineAt = clock() + rebaselineMs     // السهم يغيّر الصورة؛ أعد أخذ خط الأساس بعد ظهوره
        say("أفضل نقلة: ${m.uci()}")
    }

    private fun failAnalysis(msg: String) {
        say(msg)
        if (retries < 5) { retries++; retryAt = clock() + 2000L * retries }
    }

    /** نتيجة المحرك. تُتجاهل إن تغيّر الدور/الرقم أو الرقعة في الأثناء. */
    fun onEngineResult(forToken: Long, fen: String, move: Move?, error: String?) {
        if (forToken != token || phase != Phase.IDLE) return
        val r = rect ?: return
        if (Rules.toFen(tracker.position) != fen) return
        if (move == null) { failAnalysis(error ?: "تعذّر التحليل"); return }
        if (Rules.legalMoves(tracker.position).none { it.from == move.from && it.to == move.to }) {
            failAnalysis("أعاد المحرك نقلة غير قانونية؛ تجاهلتها"); return
        }
        retries = 0
        cache[fen] = move
        lastMove = move
        show(r, move)
    }

    // ---------- بصمة الرقعة ----------

    /** عيّنات لون على شبكة 64×64 فوق الرقعة. الفروق بالبكسل الواحد أدق من متوسط الخلايا (قطعة بيضاء على مربع فاتح تباينها ضعيف). */
    private fun fingerprint(frame: Frame, r: BoardRect): IntArray {
        val n = 64
        val out = IntArray(n * n)
        val cw = r.size / n
        for (gy in 0 until n) for (gx in 0 until n) {
            val x = (r.left + (gx + 0.5f) * cw).toInt().coerceIn(0, frame.width - 1)
            val y = (r.top + (gy + 0.5f) * cw).toInt().coerceIn(0, frame.height - 1)
            out[gy * n + gx] = frame.px(x, y) and 0xFFFFFF
        }
        return out
    }

    /** عدد النقاط التي تغيّر لونها بشكل ملحوظ. */
    private fun changed(a: IntArray, b: IntArray): Int {
        var c = 0
        for (i in a.indices) {
            val p = a[i]; val q = b[i]
            val d = abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
                abs(((p shr 8) and 255) - ((q shr 8) and 255)) +
                abs((p and 255) - (q and 255))
            if (d > 48) c++
        }
        return c
    }
}
