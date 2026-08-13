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
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts three overlay windows:
 *
 *  1. [fabView]   — small draggable floating button (always touchable).
 *                   Tap = toggle placement mode. Long-press = stop service.
 *  2. [menuView]  — mini controls (define-table / eye-toggle / reset).
 *                   Only attached while placement mode is on.
 *  3. [overlayView] — drawing surface. Its `WindowManager.LayoutParams` bounds are
 *                     intentionally **not** full-screen:
 *                       - IDLE         → 1×1 px at (0,0), not touchable.
 *                       - PLACEMENT + table defined → bound to the table rect,
 *                         touchable only inside it.
 *                       - DEFINE_TABLE / dragging corner → full screen + touchable.
 *                     This way, touches outside the table rect always reach the
 *                     app underneath, satisfying the v2 touch-pass-through goal.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var tableConfig: TableConfig
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fabView: View? = null
    private var menuView: LinearLayout? = null
    private var menuDefineBtn: Button? = null
    private var menuEyeBtn: Button? = null
    private var menuResetBtn: Button? = null
    private var overlayView: OverlayView? = null

    private var mode: OverlayView.Mode = OverlayView.Mode.IDLE
    private var referencesVisible: Boolean = true

    private val screenW: Int get() = resources.displayMetrics.widthPixels
    private val screenH: Int get() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tableConfig = TableConfig(this)
        startForegroundWithNotification()
        installFab()
        installMenu()
        installOverlayView()
        // Restore saved table rect & enter IDLE — user must tap FAB to enter PLACEMENT.
        val saved = tableConfig.loadTableRect(screenW, screenH)
        overlayView?.tableRect = saved
        applyMode(OverlayView.Mode.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listOfNotNull(fabView, menuView, overlayView).forEach { v ->
            runCatching { windowManager.removeView(v) }
        }
        fabView = null; menuView = null; overlayView = null
        menuDefineBtn = null; menuEyeBtn = null; menuResetBtn = null
        super.onDestroy()
    }

    // ========================================================
    // Foreground notification
    // ========================================================

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
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bola fantasma activo")
            .setContentText("Toca el botón flotante para colocar bolas · Mantén = detener")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.mipmap.ic_launcher, "Detener", stopIntent)

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ========================================================
    // FAB (floating action button — capa A)
    // ========================================================

    private fun installFab() {
        val fabSizePx = dp(56)
        val params = WindowManager.LayoutParams(
            fabSizePx, fabSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - fabSizePx - dp(16)
            y = dp(64)
        }

        val btn = Button(this).apply {
            text = "◎"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC14100c"))
            textSize = 16f
            isAllCaps = false
            contentDescription = "Bola fantasma (toggle / mantener = detener)"
        }

        // Drag + tap/long-press gesture detection.
        var initialX = 0; var initialY = 0
        var touchStartX = 0f; var touchStartY = 0f
        var isDragging = false
        val longPressMs = 550L
        val touchSlopPx = dp(12)
        var longPressFired = false
        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressFired = true
                stopSelf()
            }
        }

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (dx * dx + dy * dy) > touchSlopPx * touchSlopPx) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).toInt().coerceIn(0, screenW - fabSizePx)
                        params.y = (initialY + dy).toInt().coerceIn(0, screenH - fabSizePx)
                        windowManager.updateViewLayout(btn, params)
                        positionMenuNextToFab(params.x, params.y, fabSizePx)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!longPressFired && !isDragging) {
                        togglePlacement()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(btn, params)
        fabView = btn
    }

    // ========================================================
    // Menu panel (define-table / eye / reset) — placed just under FAB
    // ========================================================

    private fun installMenu() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC14100c"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val btnSize = dp(40)
        fun makeMenuBtn(label: String, desc: String, onClick: () -> Unit): Button {
            val b = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#33241708"))
                textSize = 16f
                isAllCaps = false
                contentDescription = desc
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            b.setOnClickListener { onClick() }
            return b
        }

        menuDefineBtn = makeMenuBtn("M", "Definir mesa") {
            when (mode) {
                OverlayView.Mode.IDLE, OverlayView.Mode.PLACEMENT -> applyMode(OverlayView.Mode.DEFINE_TABLE)
                OverlayView.Mode.DEFINE_TABLE -> applyMode(OverlayView.Mode.PLACEMENT)
            }
        }
        menuEyeBtn = makeMenuBtn("◉", "Mostrar/ocultar referencias") {
            referencesVisible = !referencesVisible
            overlayView?.referencesVisible = referencesVisible
            menuEyeBtn?.text = if (referencesVisible) "◉" else "○"
        }
        menuResetBtn = makeMenuBtn("⟲", "Resetear bolas") {
            overlayView?.reset()
        }

        bar.addView(menuDefineBtn)
        bar.addView(menuEyeBtn)
        bar.addView(menuResetBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        bar.visibility = View.GONE
        windowManager.addView(bar, params)
        menuView = bar
    }

    private fun positionMenuNextToFab(fabX: Int, fabY: Int, fabSize: Int) {
        val bar = menuView ?: return
        val p = bar.layoutParams as WindowManager.LayoutParams
        // Sit just under the FAB. If FAB is too low, position above.
        val belowY = fabY + fabSize + dp(8)
        val aboveY = fabY - dp(48) - dp(8)
        p.x = fabX
        p.y = if (belowY + dp(48) <= screenH) belowY else aboveY.coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(bar, p) }
    }

    // ========================================================
    // Overlay drawing surface (capa B) — sized dynamically
    // ========================================================

    private fun installOverlayView() {
        val view = OverlayView(this).apply {
            onTableRectChanged = { rect, interacting -> onTableRectChanged(rect, interacting) }
            onPlacementNeedsTable = { toast("Define primero el área de la mesa") }
            referencesVisible = this@OverlayService.referencesVisible
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    /**
     * Receives table-rect / interaction updates from [OverlayView] and resizes
     * the overlay surface accordingly:
     *   - `interacting = false` → bound to `rect` (non-touchable when in IDLE).
     *   - `interacting = true`  → bound to the full screen (touchable, via
     *                              `applyMode` during DEFINE_TABLE or while
     *                              dragging a corner).
     */
    private fun onTableRectChanged(rect: TableConfig.TableRect, interacting: Boolean) {
        if (interacting) {
            resizeOverlayTo(fullScreen = true, touchable = true)
        } else {
            resizeOverlayTo(rect = rect)
        }
    }

    private fun resizeOverlayTo(
        rect: TableConfig.TableRect? = null,
        fullScreen: Boolean = false,
        touchable: Boolean = false
    ) {
        val view = overlayView ?: return
        val p = view.layoutParams as WindowManager.LayoutParams
        if (fullScreen) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.x = 0
            p.y = 0
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else if (rect != null && rect.width > 0f && rect.height > 0f) {
            p.width = rect.width.toInt().coerceAtLeast(1)
            p.height = rect.height.toInt().coerceAtLeast(1)
            p.x = rect.left.toInt()
            p.y = rect.top.toInt()
            // touchability is controlled by applyMode()
        } else {
            p.width = 1; p.height = 1; p.x = 0; p.y = 0
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, p) }
        // Set touchability separately based on mode.
        if (!fullScreen) applyTouchability(touchable)
    }

    private fun applyTouchability(enabled: Boolean) {
        val view = overlayView ?: return
        val p = view.layoutParams as WindowManager.LayoutParams
        p.flags = if (enabled)
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    // ========================================================
    // Mode transitions
    // ========================================================

    private fun togglePlacement() {
        val newMode = if (mode == OverlayView.Mode.PLACEMENT)
            OverlayView.Mode.IDLE else OverlayView.Mode.PLACEMENT
        applyMode(newMode)
    }

    private fun applyMode(newMode: OverlayView.Mode) {
        val view = overlayView
        val fab = fabView as? Button
        if (view == null || fab == null) return
        mode = newMode
        view.mode = newMode

        when (newMode) {
            OverlayView.Mode.IDLE -> {
                fab.text = "◎"
                fab.setBackgroundColor(Color.parseColor("#CC14100c"))
                menuView?.visibility = View.GONE
                view.cancelGestures()
                resizeOverlayTo(rect = view.tableRect)
                applyTouchability(false)
            }
            OverlayView.Mode.PLACEMENT -> {
                fab.text = "✓"
                fab.setBackgroundColor(Color.parseColor("#CCc9a227"))
                menuView?.visibility = View.VISIBLE
                positionMenuNextToFab(
                    (fab.layoutParams as WindowManager.LayoutParams).x,
                    (fab.layoutParams as WindowManager.LayoutParams).y,
                    dp(56)
                )
                val rect = view.tableRect
                if (rect != null) {
                    resizeOverlayTo(rect = rect)
                    applyTouchability(true)
                } else {
                    // No table yet — overlay stays 1×1 and not touchable; hint shown in-view.
                    resizeOverlayTo(rect = null)
                }
                menuDefineBtn?.text = "M"
            }
            OverlayView.Mode.DEFINE_TABLE -> {
                fab.text = "M"
                fab.setBackgroundColor(Color.parseColor("#CCc9a227"))
                menuView?.visibility = View.GONE
                resizeOverlayTo(fullScreen = true)
                applyTouchability(true)
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        const val ACTION_STOP = "com.johan.ghostball.action.STOP"
        const val ACTION_TOGGLE = "com.johan.ghostball.action.TOGGLE"
        const val CHANNEL_ID = "ghostball_overlay_channel"
        const val NOTIF_ID = 4242
    }
}
