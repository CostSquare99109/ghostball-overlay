package com.johan.ghostball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts two overlay windows:
 *
 *  1. A small floating "trigger" button (draggable). When tapped, it toggles
 *     [drawingEnabled]. While [drawingEnabled] is true the second window
 *     receives touches (`FLAG_NOT_TOUCHABLE` cleared); otherwise touches pass
 *     through to the app underneath.
 *  2. The full-screen transparent drawing view that captures taps to place
 *     cue/object balls and draws the best shot.
 *
 * The service is mandatory because the OS aggressively kills background
 * processes that hold [WindowManager] overlay windows.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var triggerView: View? = null
    private var overlayView: OverlayView? = null

    private var drawingEnabled: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        installTriggerButton()
        installOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> toggleDrawing()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        triggerView?.let { runCatching { windowManager.removeView(it) } }
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        triggerView = null
        overlayView = null
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay activo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el overlay de bola fantasma dibujando sobre otras apps."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bola fantasma activo")
            .setContentText("Toca el botón flotante para dibujar · Mantén para mover")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.mipmap.ic_launcher,
                "Detener",
                stopIntent
            )

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun installTriggerButton() {
        val button = Button(this).apply {
            text = if (drawingEnabled) "✓" else "◎"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC14100c"))
            textSize = 14f
            setPadding(0, 0, 0, 0)
            isAllCaps = false
            contentDescription = "Bola fantasma"
        }

        val sizeDp = 56
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 240
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (dx * dx + dy * dy) > 25f) isDragging = true
                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleDrawing()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        triggerView = button
    }

    private fun installOverlayView() {
        val view = OverlayView(this).apply {
            onRequestStop = { stopSelf() }
            onRequestReset = { resetOverlay() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // Start not touchable so the user can keep using the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, params)
        overlayView = view
        applyTouchability(drawingEnabled)
    }

    private fun toggleDrawing() {
        drawingEnabled = !drawingEnabled
        applyTouchability(drawingEnabled)
        (triggerView as? Button)?.text = if (drawingEnabled) "✓" else "◎"
    }

    private fun applyTouchability(enabled: Boolean) {
        val view = overlayView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        if (enabled) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(view, params)
    }

    private fun resetOverlay() {
        overlayView?.reset()
    }

    companion object {
        const val ACTION_STOP = "com.johan.ghostball.action.STOP"
        const val ACTION_TOGGLE = "com.johan.ghostball.action.TOGGLE"
        const val CHANNEL_ID = "ghostball_overlay_channel"
        const val NOTIF_ID = 4242
    }
}
