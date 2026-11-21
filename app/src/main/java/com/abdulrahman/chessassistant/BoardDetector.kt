package com.abdulrahman.chessassistant

import kotlin.math.abs

/** إطار شاشة بكسلات ARGB. stride = عدد العناصر في الصف الواحد. */
class Frame(val pixels: IntArray, val width: Int, val height: Int, val stride: Int = width) {
    fun px(x: Int, y: Int): Int = pixels[y * stride + x]
}

/** رقعة مربعة بالبكسل. */
data class BoardRect(val left: Float, val top: Float, val size: Float) {
    val cell: Float get() = size / 8f
}

/** ألوان تصميم الرقعة (اختياري). بدونه يكتشف الكاشف الرقعة تلقائياً بأي ألوان. */
data class BoardTheme(val darkColors: List<Int>, val tolerance: Int) {
    companion object {
        val CHESSCOM_GREEN = BoardTheme(listOf(rgb(118, 150, 86), rgb(185, 202, 43)), 18)
        fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    }
}

/**
 * يكتشف أي رقعة شطرنج مسطّحة الألوان (مربعان بلونين متناوبين) دون معرفة ألوانها مسبقاً:
 * 1) يأخذ أكثر الألوان تكراراً في الشاشة.
 * 2) لكل لون يبحث عن مكوّنات متصلة شبه مربعة بحجم متقارب ويحيط بها بصندوق (كلا لوني الرقعة يلمسان أطرافها الأربعة).
 * 3) يتحقق أن الصندوق شبكة 8×8 بلونين متناوبين فعلاً، وإلا يرفضه (يمنع التقاط عناصر واجهة أخرى).
 * إن مُرّر theme يقتصر على ألوانه. لا يدعم الرقع ذات الخامات/التدرجات (خشب مصوّر مثلاً).
 */
class BoardDetector(private val theme: BoardTheme? = null, private val step: Int = 4) {

    private fun isDark(argb: Int, t: BoardTheme): Boolean {
        val r = (argb shr 16) and 255
        val g = (argb shr 8) and 255
        val b = argb and 255
        for (c in t.darkColors) {
            if (abs(r - ((c shr 16) and 255)) <= t.tolerance &&
                abs(g - ((c shr 8) and 255)) <= t.tolerance &&
                abs(b - (c and 255)) <= t.tolerance
            ) return true
        }
        return false
    }

    fun detect(frame: Frame): BoardRect? {
        val gw = frame.width / step
        val gh = frame.height / step
        if (gw < 16 || gh < 16) return null

        if (theme != null) {
            val mask = BooleanArray(gw * gh)
            for (y in 0 until gh) for (x in 0 until gw) mask[y * gw + x] = isDark(frame.px(x * step, y * step), theme)
            return rectFromMask(mask, gw, gh)?.first?.takeIf { looksLikeCheckerboard(frame, it) }
        }

        // مفاتيح الألوان: بالدقة الكاملة (الألوان المسطحة تتطابق تماماً)
        val keys = IntArray(gw * gh)
        val hist = HashMap<Int, Int>()
        for (y in 0 until gh) for (x in 0 until gw) {
            val k = frame.px(x * step, y * step) and 0xFFFFFF
            keys[y * gw + x] = k
            hist[k] = (hist[k] ?: 0) + 1
        }
        val minCount = gw * gh / 500
        val top = hist.entries.filter { it.value >= minCount }.sortedByDescending { it.value }.take(10)
        var best: Pair<BoardRect, Int>? = null
        for (e in top) {
            val mask = BooleanArray(gw * gh) { keys[it] == e.key }
            val cand = rectFromMask(mask, gw, gh) ?: continue
            if (!looksLikeCheckerboard(frame, cand.first)) continue
            if (best == null || cand.second > best.second) best = cand
        }
        return best?.first
    }

    /** مربع الصندوق المحيط بمربعات لون واحد، وعدد الأصوات (للمقارنة بين الألوان). */
    private fun rectFromMask(mask: BooleanArray, gw: Int, gh: Int): Pair<BoardRect, Int>? {
        val visited = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val comps = ArrayList<IntArray>()
        for (start in 0 until gw * gh) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % gw
                val cy = cur / gw
                count++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && mask[cur - 1] && !visited[cur - 1]) { visited[cur - 1] = true; stack[sp++] = cur - 1 }
                if (cx < gw - 1 && mask[cur + 1] && !visited[cur + 1]) { visited[cur + 1] = true; stack[sp++] = cur + 1 }
                if (cy > 0 && mask[cur - gw] && !visited[cur - gw]) { visited[cur - gw] = true; stack[sp++] = cur - gw }
                if (cy < gh - 1 && mask[cur + gw] && !visited[cur + gw]) { visited[cur + gw] = true; stack[sp++] = cur + gw }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 4 || bh < 4) continue
            val aspect = bw.toFloat() / bh
            if (aspect < 0.75f || aspect > 1.33f) continue
            if (count.toFloat() / (bw * bh) < 0.2f) continue
            comps.add(intArrayOf(minX, minY, maxX, maxY, count))
        }
        if (comps.size < 6) return null

        val sizes = comps.map { ((it[2] - it[0] + 1) + (it[3] - it[1] + 1)) / 2f }
        var bestSize = 0f
        var bestVotes = 0
        for (sz in sizes) {
            val votes = sizes.count { abs(it - sz) <= sz * 0.12f }
            if (votes > bestVotes) { bestVotes = votes; bestSize = sz }
        }
        if (bestVotes < 6) return null
        val good = comps.filterIndexed { i, _ -> abs(sizes[i] - bestSize) <= bestSize * 0.15f }
        val l = good.minOf { it[0] }
        val t = good.minOf { it[1] }
        val r = good.maxOf { it[2] } + 1
        val b = good.maxOf { it[3] } + 1
        val w = (r - l).toFloat()
        val h = (b - t).toFloat()
        if (abs(w - h) > 0.06f * maxOf(w, h)) return null
        if (abs(w - 8f * bestSize) > 0.12f * w) return null
        val size = (w + h) / 2f * step
        if (size / 8f < 16f) return null
        return Pair(BoardRect(l.toFloat() * step, t.toFloat() * step, size), bestVotes)
    }

    /** يتحقق أن الصندوق شبكة 8×8 بلونين متناوبين (أزواج التكافؤ متشابهة داخلياً ومختلفة بينها). */
    private fun looksLikeCheckerboard(frame: Frame, r: BoardRect): Boolean {
        if (r.left < 0 || r.top < 0 || r.left + r.size > frame.width + 2 || r.top + r.size > frame.height + 2) return false
        val cell = r.cell
        val cols = Array(64) { IntArray(3) }
        for (row in 0..7) for (col in 0..7) {
            val pts = arrayOf(floatArrayOf(0.5f, 0.1f), floatArrayOf(0.5f, 0.9f), floatArrayOf(0.1f, 0.5f), floatArrayOf(0.9f, 0.5f))
            val rs = IntArray(4); val gs = IntArray(4); val bs = IntArray(4)
            for ((i, pt) in pts.withIndex()) {
                val x = (r.left + (col + pt[0]) * cell).toInt().coerceIn(0, frame.width - 1)
                val y = (r.top + (row + pt[1]) * cell).toInt().coerceIn(0, frame.height - 1)
                val p = frame.px(x, y)
                rs[i] = (p shr 16) and 255; gs[i] = (p shr 8) and 255; bs[i] = p and 255
            }
            rs.sort(); gs.sort(); bs.sort()
            cols[row * 8 + col] = intArrayOf(rs[2], gs[2], bs[2])
        }
        val med = Array(2) { IntArray(3) }
        for (par in 0..1) for (ch in 0..2) {
            val v = (0..63).filter { ((it / 8) + (it % 8)) % 2 == par }.map { cols[it][ch] }.sorted()
            med[par][ch] = v[v.size / 2]
        }
        fun dist(a: IntArray, b: IntArray) = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
        if (dist(med[0], med[1]) < 30) return false
        var ok = 0
        for (i in 0..63) if (dist(cols[i], med[((i / 8) + (i % 8)) % 2]) <= 40) ok++
        return ok >= 52
    }
}
