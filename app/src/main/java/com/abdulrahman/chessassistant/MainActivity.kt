package com.abdulrahman.chessassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var depthLabel: TextView
    private val captureRequest = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS, MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }
        val title = TextView(this).apply { text = "Chess Assistant"; textSize = 26f }
        status = TextView(this).apply {
            textSize = 16f
            text = "ابدأ اللعبة من الوضعية الابتدائية ثم شغّل المساعد."
            setPadding(0, 24, 0, 24)
        }
        val permissionButton = Button(this).apply {
            text = "السماح بالظهور فوق التطبيقات"
            setOnClickListener { requestOverlayPermission() }
        }
        val startButton = Button(this).apply {
            text = "تشغيل المساعد"
            setOnClickListener { startAssistant() }
        }
        val stopButton = Button(this).apply {
            text = "إيقاف المساعد"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                status.text = "تم إيقاف المساعد."
            }
        }
        depthLabel = TextView(this).apply { setPadding(0, 24, 0, 0) }
        val depth = prefs.getInt(ScreenCaptureService.PREF_DEPTH, 12).coerceIn(1, 15)
        depthLabel.text = "عمق التحليل: $depth (يسري عند التشغيل التالي)"
        val seek = SeekBar(this).apply {
            max = 14
            progress = depth - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    val d = value + 1
                    depthLabel.text = "عمق التحليل: $d (يسري عند التشغيل التالي)"
                    prefs.edit().putInt(ScreenCaptureService.PREF_DEPTH, d).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val demoButton = Button(this).apply {
            text = "سهم تجريبي"
            setOnClickListener { showDemoArrow() }
        }
        val hideButton = Button(this).apply {
            text = "إخفاء السهم التجريبي"
            setOnClickListener { stopService(Intent(this@MainActivity, ArrowOverlayService::class.java)) }
        }

        val resetRectButton = Button(this).apply {
            text = "مسح موضع الرقعة المحفوظ"
            setOnClickListener {
                ScreenCaptureService.clearSavedRect(this@MainActivity)
                status.text = "تم المسح. عند التشغيل القادم سيبحث عن الرقعة ثم يطلب تحديدها إن لزم."
            }
        }
        listOf(title, status, permissionButton, startButton, stopButton, resetRectButton, depthLabel, seek, demoButton, hideButton)
            .forEach { layout.addView(it) }
        setContentView(layout)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            status.text = "إذن الظهور فوق التطبيقات مفعّل."
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun showDemoArrow() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "فعّل الإذن أولاً ثم ارجع للتطبيق."
            requestOverlayPermission()
            return
        }
        startService(Intent(this, ArrowOverlayService::class.java).putExtra(ArrowOverlayService.EXTRA_DEMO, true))
    }

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "فعّل إذن الظهور فوق التطبيقات أولاً."
            requestOverlayPermission()
            return
        }
        stopService(Intent(this, ArrowOverlayService::class.java)) // أزل السهم التجريبي إن وُجد
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Deprecated by Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest) return
        if (resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
            status.text = "المساعد يعمل. انتقل إلى لعبة الشطرنج (الحالة في الإشعار)."
        } else {
            status.text = "لم يتم منح إذن التقاط الشاشة."
        }
    }
}
