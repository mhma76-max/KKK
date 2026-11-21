package com.abdulrahman.chessassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * بعد موافقة المستخدم على MediaProjection: يلتقط الشاشة، ويشغّل AssistantCore على خيط معالجة واحد،
 * ويرسل طلبات المحرك على خيط شبكة منفصل. المعالجة محدودة بإطار كل ~200ms حفاظاً على المعالج والبطارية.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val PREFS = "chess_assistant"
        const val PREF_DEPTH = "depth"
        private const val CHANNEL_ID = "chess_capture"
        private const val NOTIFICATION_ID = 71
        private const val TRACK_INTERVAL_MS = 200L
        private const val SEARCH_INTERVAL_MS = 700L
        private const val PREF_RECT = "board_rect"      // "left,top,size,screenW,screenH"

        @Volatile private var active: ScreenCaptureService? = null

        /** يُستدعى من BoardSelectorService عند تأكيد المستخدم. */
        fun deliverBoardRect(r: BoardRect) {
            val svc = active ?: return
            svc.handler?.post {
                svc.core?.setManualRect(r)
                svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_RECT, "${r.left},${r.top},${r.size},${svc.screenW},${svc.screenH}").apply()
            }
        }

        fun clearSavedRect(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_RECT).apply()
        }
    }

    private var screenW = 0
    private var screenH = 0

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var netExecutor: ExecutorService? = null
    private var core: AssistantCore? = null
    private var stopping = false
    private var lastFrame: Frame? = null
    private var savedFirstFrame = false
    private var pixels: IntArray? = null
    private var bitmap: Bitmap? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+: يجب استدعاء startForeground بنوع mediaProjection قبل getMediaProjection
        startCaptureNotification("التقاط الشاشة مفعّل بإذنك")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, resultData)
            projection = mp
            // Android 14+: التسجيل قبل createVirtualDisplay إلزامي
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) stopSelf()
                }
            }, Handler(mainLooper))

            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            val width = bounds.width()
            val height = bounds.height()
            screenW = width
            screenH = height
            val dpi = resources.configuration.densityDpi

            worker = HandlerThread("ChessScreenCapture").also { it.start() }
            val h = Handler(worker!!.looper)
            handler = h
            netExecutor = Executors.newSingleThreadExecutor()

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val depth = prefs.getInt(PREF_DEPTH, 12)
            val client = StockfishClient(depth = depth)
            val newCore = AssistantCore(object : CoreOutput {
                override fun showArrow(rect: BoardRect, flipped: Boolean, move: Move) {
                    val g = BoardGeometry(rect.left, rect.top, rect.size, flipped)
                    val a = g.center(move.fromFile, move.fromRank)
                    val b = g.center(move.toFile, move.toRank)
                    ArrowOverlayService.showArrow(a.first, a.second, b.first, b.second)
                }

                override fun hideArrow() = ArrowOverlayService.hideArrow()

                override fun requestAnalysis(fen: String, token: Long) {
                    netExecutor?.execute {
                        val res = client.bestMove(fen)
                        handler?.post { core?.onEngineResult(token, fen, res.move, res.error) }
                    }
                }

                override fun status(text: String) = updateNotification(text)

                override fun debug(text: String) {
                    debugText = text
                    updateNotification("تشخيص (افتح الإشعار)")
                }

                override fun requestBoardSelection() {
                    startService(Intent(this@ScreenCaptureService, BoardSelectorService::class.java))
                }

                override fun cancelBoardSelection() {
                    stopService(Intent(this@ScreenCaptureService, BoardSelectorService::class.java))
                }

                override fun manualRectRejected() {
                    clearSavedRect(this@ScreenCaptureService)
                }
            })
            // موضع الرقعة المحفوظ (إن كان لنفس أبعاد الشاشة)
            prefs.getString(PREF_RECT, null)?.split(',')?.let { v ->
                if (v.size == 5 && v[3].toIntOrNull() == width && v[4].toIntOrNull() == height) {
                    val l = v[0].toFloatOrNull(); val t = v[1].toFloatOrNull(); val sz = v[2].toFloatOrNull()
                    if (l != null && t != null && sz != null) newCore.setManualRect(BoardRect(l, t, sz))
                }
            }
            active = this
            core = newCore

            // السهم يُشغَّل بلا سهم تجريبي
            startService(Intent(this, ArrowOverlayService::class.java))

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            // مؤقّت بدل مستمع الإطارات: الشاشة الثابتة لا تنتج إطارات جديدة، فلو اعتمدنا على وصول الإطارات
            // لتوقفت إعادة الفحص والاستقرار عند "أعيد الفحص…". نأخذ آخر إطار إن وُجد ونعيد معالجة الأخير.
            val tick = object : Runnable {
                override fun run() {
                    if (stopping) return
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            try {
                                val plane = image.planes[0]
                                val pixelStride = plane.pixelStride
                                val fullWidth = width + (plane.rowStride - pixelStride * width) / pixelStride
                                var bmp = bitmap
                                if (bmp == null || bmp.width != fullWidth) {
                                    bmp = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
                                    bitmap = bmp
                                    pixels = IntArray(fullWidth * height)
                                }
                                bmp!!.copyPixelsFromBuffer(plane.buffer)
                                val px = pixels!!
                                bmp.getPixels(px, 0, fullWidth, 0, 0, fullWidth, height)
                                lastFrame = Frame(px, width, height, fullWidth)
                                if (!savedFirstFrame) {
                                    savedFirstFrame = true
                                    saveDebugFrame(bmp, width, height)
                                }
                            } finally {
                                image.close()
                            }
                        }
                        val f = lastFrame
                        if (f != null) {
                            core?.onFrame(f)
                        }
                    } catch (e: Exception) {
                        updateNotification("خطأ في المعالجة: ${e.message}")
                    }
                    val interval = if (core?.isTracking == true) TRACK_INTERVAL_MS else SEARCH_INTERVAL_MS
                    h.postDelayed(this, interval)
                }
            }
            h.postDelayed(tick, 300)

            virtualDisplay = mp.createVirtualDisplay(
                "ChessAssistantCapture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, h
            )
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "فشل بدء الالتقاط: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun saveDebugFrame(full: Bitmap, width: Int, height: Int) {
        try {
            val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
            FileOutputStream(File(cacheDir, "latest_screen_capture.png")).use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            cropped.recycle()
        } catch (_: Exception) {}
    }

    private var debugText = ""

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Assistant")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(if (debugText.isEmpty()) text else "$text\n$debugText"))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOnlyAlertOnce(true)
            .build()

    private fun startCaptureNotification(text: String) {
        val channel = NotificationChannel(CHANNEL_ID, "Chess screen capture", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopping = true
        if (active === this) active = null
        stopService(Intent(this, BoardSelectorService::class.java))
        ArrowOverlayService.hideArrow()
        stopService(Intent(this, ArrowOverlayService::class.java))
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        netExecutor?.shutdownNow()
        worker?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
