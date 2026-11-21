package com.abdulrahman.chessassistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class EngineResult(val move: Move?, val error: String?)

/**
 * عميل خادم Stockfish عبر stockfish.online (GET .../api/s/v2.php?fen=&depth=).
 * تحقّقت من توثيق الموقع: العمق < 16، والاستجابة JSON فيها success وbestmove ("bestmove b7b6 ponder f3e5").
 * أسماء المعاملات والمسار نفسه لم أتحقق منها بطلب فعلي (لا إنترنت في بيئة البناء) — الاستخدام في تطبيقات
 * أخرى يطابق ما كتبته. الخدمة طرف ثالث بلا ضمان؛ يمكن تغيير endpoint لخادم خاص يطابق الصيغة نفسها.
 * يجب تشغيله خارج الـ Main thread.
 */
class StockfishClient(
    private val endpoint: String = "https://stockfish.online/api/s/v2.php",
    depth: Int = 12,
    private val timeoutMs: Int = 15_000,
    private val retries: Int = 2
) {
    private val depth = depth.coerceIn(1, 15)

    fun bestMove(fen: String): EngineResult {
        val pos = Rules.fromFen(fen)
        if (pos == null || Rules.validate(pos) != null) return EngineResult(null, "FEN غير موثوق؛ لم يُرسل")
        var lastError = "تعذّر الاتصال"
        for (attempt in 0..retries) {
            try {
                val url = URL("$endpoint?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=$depth")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.setRequestProperty("User-Agent", "ChessAssistant/0.3")
                    val code = conn.responseCode
                    if (code != 200) {
                        lastError = "خطأ HTTP $code"
                        if (code in 400..499 && code != 429) return EngineResult(null, lastError)
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val move = parseBestMove(body)
                        if (move != null) return EngineResult(move, null)
                        lastError = "استجابة بلا نقلة"
                        if (body.contains("\"success\":false") || body.contains("\"success\": false")) {
                            return EngineResult(null, "رفض الخادم الطلب")
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                lastError = "خطأ شبكة: ${e.message}"
            }
            if (attempt < retries) {
                try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) { return EngineResult(null, "أُلغي") }
            }
        }
        return EngineResult(null, lastError)
    }

    companion object {
        private val BEST = Regex("bestmove\\s+([a-h][1-8][a-h][1-8][qrbn]?)")

        fun parseBestMove(response: String): Move? =
            BEST.find(response)?.let { Move.parseUci(it.groupValues[1]) }
    }
}
