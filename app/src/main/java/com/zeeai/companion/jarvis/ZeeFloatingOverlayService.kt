package com.zeeai.companion.jarvis

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zeeai.companion.MainActivity
import com.zeeai.companion.automation.ZeeAccessibilityService
import kotlin.math.abs

/**
 * Floating Arc-Reactor Glass HUD Bubble Service (System Overlay)
 * Survives across any app on screen; tap bubble to expand compact Screen Summary & Voice controls.
 */
class ZeeFloatingOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "zee_overlay_hud_channel"
        private const val NOTIF_ID = 4109
        const val ACTION_UPDATE_STATE = "com.zeeai.companion.OVERLAY_UPDATE_STATE"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_SUMMARY = "summary"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, ZeeFloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ZeeFloatingOverlayService::class.java))
        }

        fun updateState(context: Context, phase: String, summary: String? = null) {
            val intent = Intent(context, ZeeFloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_PHASE, phase)
                if (summary != null) putExtra(EXTRA_SUMMARY, summary)
            }
            if (isRunning) {
                runCatching { context.startService(intent) }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var rootContainer: LinearLayout? = null
    private var orbView: ArcReactorOrbView? = null
    private var expandedPanel: LinearLayout? = null
    private var statusTitleView: TextView? = null
    private var summaryBodyView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        attachOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_STATE) {
            val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
            val summary = intent.getStringExtra(EXTRA_SUMMARY)
            orbView?.setPhase(phase)
            if (!summary.isNullOrBlank()) {
                statusTitleView?.text = "ZEE AI // $phase"
                summaryBodyView?.text = summary
                if (!isExpanded) toggleExpanded(true)
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlayWindow() {
        val density = resources.displayMetrics.density
        val orbSizePx = (66 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val orb = ArcReactorOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSizePx, orbSizePx)
        }

        val panel = buildExpandedPanel(density).apply {
            visibility = View.GONE
        }

        container.addView(orb)
        container.addView(panel)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (220 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        orb.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = ev.rawX
                    initialTouchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - initialTouchX).toInt()
                    val dy = (ev.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleExpanded(!isExpanded)
                    }
                    true
                }
                else -> false
            }
        }

        rootContainer = container
        orbView = orb
        expandedPanel = panel
        layoutParams = params
        runCatching { windowManager.addView(container, params) }
    }

    private fun buildExpandedPanel(density: Float): LinearLayout {
        val pad = (12 * density).toInt()
        val panelBg = GradientDrawable().apply {
            setColor(Color.argb(232, 8, 13, 26))
            cornerRadius = 18 * density
            setStroke((1.2f * density).toInt(), Color.argb(140, 0, 229, 255))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (235 * density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8 * density).toInt()
            }

            val title = TextView(this@ZeeFloatingOverlayService).apply {
                text = "ZEE AI // HOLOGRAM OVERLAY"
                setTextColor(Color.rgb(0, 229, 255))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }
            statusTitleView = title

            val body = TextView(this@ZeeFloatingOverlayService).apply {
                text = "Tap 'Read Screen' to analyze the active app, or launch Voice HUD."
                setTextColor(Color.rgb(226, 232, 240))
                textSize = 11.5f
                setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
            }
            summaryBodyView = body

            val row1 = LinearLayout(this@ZeeFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnScreen = makeGlassButton("Read Screen", Color.rgb(0, 229, 255), density) {
                statusTitleView?.text = "ZEE AI // SCANNING..."
                summaryBodyView?.text = "Extracting foreground screen nodes..."
                ZeeAccessibilityService.sendControlCommand(this@ZeeFloatingOverlayService, "SNAPSHOT_SCREEN")
            }

            val btnOpen = makeGlassButton("Open HUD", Color.rgb(129, 140, 248), density) {
                val launch = Intent(this@ZeeFloatingOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_hud_mic", true)
                }
                startActivity(launch)
                toggleExpanded(false)
            }

            row1.addView(btnScreen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * density).toInt()
            })
            row1.addView(btnOpen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val btnStopMic = makeGlassButton("Stop Hands-Free Mic", Color.rgb(245, 158, 11), density) {
                com.zeeai.companion.voice.WakeWordEventBus.setPhoneMicSessionDesired(false)
                com.zeeai.companion.voice.WakeWordForegroundService.stop(this@ZeeFloatingOverlayService)
                orbView?.setPhase("IDLE")
                statusTitleView?.text = "ZEE AI // MIC STOPPED"
                summaryBodyView?.text = "Hands-free mic stopped. Tap Open HUD to restart."
            }

            val btnClose = makeGlassButton("Dismiss Orb", Color.rgb(255, 82, 82), density) {
                JarvisPreferences(this@ZeeFloatingOverlayService).update { it.copy(overlayBubbleActive = false) }
                stopSelf()
            }

            addView(title)
            addView(body)
            addView(row1)
            addView(btnStopMic, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            })
            addView(btnClose, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            })
        }
    }

    private fun makeGlassButton(label: String, accent: Int, density: Float, onClick: () -> Unit): TextView {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(44, Color.red(accent), Color.green(accent), Color.blue(accent)))
            cornerRadius = 11 * density
            setStroke((1 * density).toInt(), Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            background = bg
            setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun toggleExpanded(show: Boolean) {
        isExpanded = show
        expandedPanel?.visibility = if (show) View.VISIBLE else View.GONE
        val root = rootContainer
        val params = layoutParams
        if (root != null && params != null) {
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    override fun onDestroy() {
        isRunning = false
        orbView?.stopAnimator()
        rootContainer?.let { runCatching { windowManager.removeView(it) } }
        rootContainer = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "ZEE AI Floating Overlay", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ZEE AI Hologram Overlay Active")
            .setContentText("Tap floating orb anytime for instant screen reading & voice HUD")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private class ArcReactorOrbView(context: Context) : View(context) {
        private var angle = 0f
        private var accentColor = Color.rgb(0, 229, 255)

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arcBounds = RectF()

        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        fun setPhase(phase: String) {
            accentColor = when (phase.uppercase()) {
                "LISTENING" -> Color.rgb(0, 230, 118)
                "THINKING", "EXECUTING" -> Color.rgb(255, 179, 0)
                "SPEAKING" -> Color.rgb(186, 104, 200)
                "ALERT" -> Color.rgb(255, 23, 68)
                else -> Color.rgb(0, 229, 255)
            }
            invalidate()
        }

        fun stopAnimator() {
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 6f
            if (r <= 4f) return

            val rC = Color.red(accentColor)
            val gC = Color.green(accentColor)
            val bC = Color.blue(accentColor)

            // Outer smoked obsidian shield
            haloPaint.color = Color.argb(230, 4, 8, 18)
            canvas.drawCircle(cx, cy, r, haloPaint)

            // Luminous outer halo
            ringPaint.color = Color.argb(95, rC, gC, bC)
            ringPaint.strokeWidth = 3f
            canvas.drawCircle(cx, cy, r - 2f, ringPaint)

            // Rotating segmented Arc Reactor arcs
            arcBounds.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
            ringPaint.color = accentColor
            ringPaint.strokeWidth = 4.5f
            canvas.drawArc(arcBounds, angle, 75f, false, ringPaint)
            canvas.drawArc(arcBounds, angle + 180f, 75f, false, ringPaint)

            // Inner counter-rotating ring
            val rIn = r * 0.52f
            arcBounds.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn)
            ringPaint.color = Color.argb(190, 255, 255, 255)
            ringPaint.strokeWidth = 2.6f
            canvas.drawArc(arcBounds, -angle * 1.4f, 110f, false, ringPaint)
            canvas.drawArc(arcBounds, -angle * 1.4f + 190f, 110f, false, ringPaint)

            // Bright central singularity
            corePaint.color = Color.argb(110, rC, gC, bC)
            canvas.drawCircle(cx, cy, r * 0.34f, corePaint)
            corePaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.16f, corePaint)
        }
    }
}
