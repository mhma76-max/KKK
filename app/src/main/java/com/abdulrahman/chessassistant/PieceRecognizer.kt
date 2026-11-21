package com.abdulrahman.chessassistant

import kotlin.math.abs

/** نتيجة تحليل مربع واحد. */
class SquareSample(val silhouette: FloatArray, val occupancy: Float, val luminance: Float)

data class PieceClassification(val piece: Char, val confidence: Float, val distance: Float)

/**
 * تعرّف على القطع بدون نموذج مدرّب مسبقاً: نستخرج "ظل" القطعة (silhouette) بعد طرح لون خلفية المربع
 * وملء الثقوب، ونقارنه بقوالب تُتعلَّم من الوضعية الابتدائية (نعرف مكان كل قطعة فيها).
 * اللون (أبيض/أسود) من وسيط سطوع بكسلات الظل. لا يعتمد على لون المربع.
 */
class PieceRecognizer {
    companion object {
        const val G = 32            // دقة الشبكة الداخلية
        const val T = 16            // دقة القالب
        const val EMPTY_THRESHOLD = 0.18f
        private const val FG_DIFF = 70
        private const val TYPES = "pnbrqk"
    }

    private val templates = arrayOfNulls<FloatArray>(6)
    // Keep the individual examples from this game's starting position as well as the mean.
    // Comparing against the closest exemplar avoids blurring two slightly different 2D sprites.
    private val exemplars = Array(6) { ArrayList<FloatArray>() }
    private var lumThreshold = 128f
    val isCalibrated: Boolean get() = templates.all { it != null } && exemplars.all { it.isNotEmpty() }

    private fun inExcluded(u: Float, v: Float) = (u < 0.24f && v < 0.24f) || (u > 0.76f && v > 0.76f)

    fun analyze(frame: Frame, rect: BoardRect, col: Int, row: Int): SquareSample {
        val cell = rect.cell
        val x0 = rect.left + col * cell
        val y0 = rect.top + row * cell
        val inset = cell * 0.06f
        val xs = (x0 + inset).toInt().coerceAtLeast(0)
        val ys = (y0 + inset).toInt().coerceAtLeast(0)
        val xe = (x0 + cell - inset).toInt().coerceAtMost(frame.width)
        val ye = (y0 + cell - inset).toInt().coerceAtMost(frame.height)
        val cw = xe - xs
        val ch = ye - ys
        if (cw < 8 || ch < 8) return SquareSample(FloatArray(T * T), 0f, 0f)

        // خلفية المربع: وسيط ألوان منتصف الأضلاع (بعيداً عن زوايا الإحداثيات)
        val rs = IntArray(96); val gs = IntArray(96); val bs = IntArray(96)
        var n = 0
        for (i in 0 until 24) {
            val t = 0.25f + 0.5f * i / 23f
            val px = xs + ((cw - 1) * t).toInt()
            val py = ys + ((ch - 1) * t).toInt()
            val pts = intArrayOf(
                frame.px(px, ys), frame.px(px, ye - 1), frame.px(xs, py), frame.px(xe - 1, py)
            )
            for (p in pts) {
                rs[n] = (p shr 16) and 255; gs[n] = (p shr 8) and 255; bs[n] = p and 255; n++
            }
        }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[48]; val bg = gs[48]; val bb = bs[48]

        val fg = IntArray(G * G)
        val tot = IntArray(G * G)
        for (y in ys until ye) {
            val v = (y - ys).toFloat() / ch
            val by = ((y - ys) * G / ch).coerceAtMost(G - 1)
            for (x in xs until xe) {
                val u = (x - xs).toFloat() / cw
                if (inExcluded(u, v)) continue
                val bx = ((x - xs) * G / cw).coerceAtMost(G - 1)
                val p = frame.px(x, y)
                val d = abs(((p shr 16) and 255) - br) + abs(((p shr 8) and 255) - bg) + abs((p and 255) - bb)
                tot[by * G + bx]++
                if (d > FG_DIFF) fg[by * G + bx]++
            }
        }
        val mask = BooleanArray(G * G) { tot[it] > 0 && fg[it] * 10 >= tot[it] * 3 }

        // تمدد 3x3 ثم ملء الثقوب بطوفان من الحدود
        val dil = BooleanArray(G * G)
        for (y in 0 until G) for (x in 0 until G) {
            if (!mask[y * G + x]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until G && xx in 0 until G) dil[yy * G + xx] = true
            }
        }
        val outside = BooleanArray(G * G)
        val stack = IntArray(G * G)
        var sp = 0
        fun seed(i: Int) { if (!dil[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (i in 0 until G) { seed(i); seed((G - 1) * G + i); seed(i * G); seed(i * G + G - 1) }
        while (sp > 0) {
            val cur = stack[--sp]
            val cx = cur % G; val cy = cur / G
            if (cx > 0) seed(cur - 1)
            if (cx < G - 1) seed(cur + 1)
            if (cy > 0) seed(cur - G)
            if (cy < G - 1) seed(cur + G)
        }
        var silCount = 0
        for (i in 0 until G * G) if (!outside[i]) silCount++
        val occupancy = silCount.toFloat() / (G * G)

        val sil = FloatArray(T * T)
        for (y in 0 until T) for (x in 0 until T) {
            var s = 0
            for (dy in 0..1) for (dx in 0..1) if (!outside[(y * 2 + dy) * G + x * 2 + dx]) s++
            sil[y * T + x] = s / 4f
        }

        // وسيط سطوع "قلب" القطعة: نستبعد حلقة التمدد والإطار (كتل ضمن بعدين من الخارج) حتى لا يطغى لون الخلفية
        var lum = 0f
        if (occupancy >= EMPTY_THRESHOLD) {
            val inner = BooleanArray(G * G)
            var nInner = 0
            for (y in 0 until G) for (x in 0 until G) {
                if (outside[y * G + x]) continue
                var ok = true
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    val yy = y + dy
                    val xx = x + dx
                    if (yy < 0 || xx < 0 || yy >= G || xx >= G || outside[yy * G + xx]) { ok = false; break@loop }
                }
                inner[y * G + x] = ok
                if (ok) nInner++
            }
            val useInner = nInner >= 8
            val hist = IntArray(256)
            var cnt = 0
            for (y in ys until ye) {
                val by = ((y - ys) * G / ch).coerceAtMost(G - 1)
                for (x in xs until xe) {
                    val bx = ((x - xs) * G / cw).coerceAtMost(G - 1)
                    val i = by * G + bx
                    if (if (useInner) !inner[i] else outside[i]) continue
                    val p = frame.px(x, y)
                    val l = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
                    hist[l]++; cnt++
                }
            }
            var acc = 0
            for (l in 0..255) { acc += hist[l]; if (acc * 2 >= cnt) { lum = l.toFloat(); break } }
        }
        return SquareSample(sil, occupancy, lum)
    }

    /**
     * معايرة من الوضعية الابتدائية. samples: عينة لكل مربع من 64 مع الحرف المتوقع ('.' للفارغ).
     * يرجع false إذا لم تكن العينات متسقة (مثلاً أبيض وأسود بسطوع متقارب).
     */
    fun calibrate(samples: List<Pair<Char, SquareSample>>): Boolean {
        val sums = Array(6) { FloatArray(T * T) }
        val counts = IntArray(6)
        val nextExemplars = Array(6) { ArrayList<FloatArray>() }
        var wl = 0f; var wn = 0; var bl = 0f; var bn = 0
        for ((c, s) in samples) {
            if (c == '.') continue
            val i = TYPES.indexOf(c.lowercaseChar())
            if (i < 0 || s.occupancy < EMPTY_THRESHOLD) return false
            for (k in 0 until T * T) sums[i][k] += s.silhouette[k]
            // Store the actual per-game examples; do not collapse both rooks/knights/etc. into one blurred mask.
            nextExemplars[i].add(s.silhouette.copyOf())
            counts[i]++
            if (Rules.isWhite(c)) { wl += s.luminance; wn++ } else { bl += s.luminance; bn++ }
        }
        if (counts.any { it == 0 } || wn == 0 || bn == 0) return false
        val whiteLum = wl / wn
        val blackLum = bl / bn
        if (whiteLum - blackLum < 40f) return false
        for (i in 0 until 6) {
            templates[i] = FloatArray(T * T) { sums[i][it] / counts[i] }
            exemplars[i].clear()
            exemplars[i].addAll(nextExemplars[i])
        }
        lumThreshold = (whiteLum + blackLum) / 2f
        return true
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (k in 0 until T * T) {
            val e = a[k] - b[k]
            d += e * e
        }
        return d / (T * T)
    }

    /** Best visual match plus a confidence estimate in [0,1]. */
    fun classifyDetailed(s: SquareSample): PieceClassification? {
        if (!isCalibrated || s.occupancy < EMPTY_THRESHOLD) return null
        val bestByType = FloatArray(6) { Float.MAX_VALUE }
        for (i in 0 until 6) {
            val candidates = exemplars[i]
            for (template in candidates) {
                val d = distance(s.silhouette, template)
                if (d < bestByType[i]) bestByType[i] = d
            }
            // Include the mean as a stabilizer for antialiasing/compression differences.
            templates[i]?.let { val d = distance(s.silhouette, it); if (d < bestByType[i]) bestByType[i] = d }
        }
        var best = -1
        var bestD = Float.MAX_VALUE
        var secondD = Float.MAX_VALUE
        for (i in 0 until 6) {
            val d = bestByType[i]
            if (d < bestD) { secondD = bestD; bestD = d; best = i }
            else if (d < secondD) secondD = d
        }
        if (best < 0 || !bestD.isFinite()) return null
        val margin = secondD - bestD
        val confidence = (1f - bestD / 0.25f).coerceIn(0f, 1f) *
            (0.55f + (margin / 0.04f).coerceIn(0f, 0.45f))
        val piece = if (s.luminance > lumThreshold) TYPES[best].uppercaseChar() else TYPES[best]
        return PieceClassification(piece, confidence, bestD)
    }

    /** Strict path used by the live assistant: uncertain occupied squares are unknown, never guessed. */
    fun classifyConfident(s: SquareSample, minConfidence: Float = 0.48f): Char? {
        if (s.occupancy < EMPTY_THRESHOLD) return '.'
        val result = classifyDetailed(s) ?: return null
        if (result.distance > 0.20f || result.confidence < minConfidence) return null
        return result.piece
    }

    /** Backward-compatible best-match method for diagnostics and existing tests. */
    fun classify(s: SquareSample): Char {
        if (s.occupancy < EMPTY_THRESHOLD) return '.'
        return classifyDetailed(s)?.piece ?: '.'
    }

    fun isWhiteLuminance(l: Float) = l > lumThreshold

    /** تصدير القوالب لحفظها بين الجلسات (تتيح البدء من منتصف مباراة بنفس تصميم الرقعة). */
    fun export(): String {
        val sb = StringBuilder("v2;").append(lumThreshold)
        for (i in 0 until 6) {
            sb.append(';').append(templates[i]!!.joinToString(",") { "%.3f".format(java.util.Locale.US, it) })
            sb.append(';').append(exemplars[i].joinToString("|") { sample ->
                sample.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
            })
        }
        return sb.toString()
    }

    fun import(text: String): Boolean {
        val parts = text.split(';')
        val lum = parts.getOrNull(1)?.toFloatOrNull() ?: return false
        if (parts[0] == "v1") {
            if (parts.size != 8) return false
            val tmp = arrayOfNulls<FloatArray>(6)
            for (i in 0 until 6) {
                val vals = parts[i + 2].split(',').mapNotNull { it.toFloatOrNull() }
                if (vals.size != T * T) return false
                tmp[i] = vals.toFloatArray()
            }
            for (i in 0 until 6) { templates[i] = tmp[i]; exemplars[i].clear(); exemplars[i].add(tmp[i]!!.copyOf()) }
            lumThreshold = lum
            return true
        }
        if (parts[0] != "v2" || parts.size != 14) return false
        val tmp = arrayOfNulls<FloatArray>(6)
        val samples = Array(6) { ArrayList<FloatArray>() }
        for (i in 0 until 6) {
            val mean = parts[2 + i * 2].split(',').mapNotNull { it.toFloatOrNull() }
            if (mean.size != T * T) return false
            tmp[i] = mean.toFloatArray()
            val raw = parts[3 + i * 2]
            for (entry in raw.split('|')) {
                val values = entry.split(',').mapNotNull { it.toFloatOrNull() }
                if (values.size != T * T) return false
                samples[i].add(values.toFloatArray())
            }
            if (samples[i].isEmpty()) return false
        }
        for (i in 0 until 6) { templates[i] = tmp[i]; exemplars[i].clear(); exemplars[i].addAll(samples[i]) }
        lumThreshold = lum
        return true
    }
}
