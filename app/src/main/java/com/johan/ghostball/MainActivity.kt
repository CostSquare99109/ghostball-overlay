package com.johan.ghostball

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Single-screen host:
 *  - Checks + requests SYSTEM_ALERT_WINDOW.
 *  - On Android 13+, requests POST_NOTIFICATIONS (needed for the foreground service).
 *  - Launches the [OverlayService] when the user grants overlay permission.
 *
 * The UI is built in code (no XML) on purpose: keeps the APK lean and avoids
 * dependency on ConstraintLayout for what is essentially three buttons.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        startButton.setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14100c"))
            setPadding(48, 96, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "◈ Ghost Ball Overlay"
            textSize = 24f
            setTextColor(Color.parseColor("#c9a227"))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title, layoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))

        val sub = TextView(this).apply {
            text = Html.fromHtml(
                "Overlay flotante para billar 8-ball. " +
                    "Marca la bola blanca y la objetivo sobre cualquier app " +
                    "(juego, videollamada) y dibuja la trayectoria más fácil " +
                    "a una de las 6 troneras. " +
                    "<br><br><b>Permisos:</b> " +
                    "<br>• <b>Mostrar sobre otras apps</b>: para dibujar el overlay.<br>" +
                    "• <b>Notificación</b>: obligatoria para que el servicio de overlay " +
                    "pueda estar activo en primer plano.",
                Html.FROM_HTML_MODE_LEGACY
            )
            textSize = 14f
            setTextColor(Color.parseColor("#eae3d3"))
            movementMethod = LinkMovementMethod.getInstance()
        }
        root.addView(sub, layoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#9c9284"))
            gravity = Gravity.CENTER
        }
        root.addView(statusText, layoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))

        startButton = Button(this).apply {
            text = "Conceder permiso de overlay"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#c9a227"))
            setTextColor(Color.parseColor("#14100c"))
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        root.addView(startButton, layoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val note = TextView(this).apply {
            text = "Tras conceder, pulsa el botón otra vez para iniciar el overlay."
            textSize = 11f
            setTextColor(Color.parseColor("#8a8071"))
            gravity = Gravity.CENTER
        }
        root.addView(note, layoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))

        return root
    }

    private fun layoutParams(w: Int, h: Int) =
        LinearLayout.LayoutParams(w, h).apply {
            val m = dp(8)
            setMargins(0, m, 0, m)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshPermissionState() {
        val canOverlay = Settings.canDrawOverlays(this)
        statusText.text = if (canOverlay) {
            "Permiso de overlay concedido. Pulsa para iniciar."
        } else {
            "Permiso de overlay no concedido. Pulsa para abrir Ajustes."
        }
        startButton.text = if (canOverlay) "Iniciar overlay" else "Conceder permiso de overlay"
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        launchOverlayService()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQ_OVERLAY)
    }

    @Deprecated("Deprecated in Java but required on minSdk 26.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            refreshPermissionState()
        }
    }

    private fun launchOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

    companion object {
        private const val REQ_OVERLAY = 1001
    }
}
