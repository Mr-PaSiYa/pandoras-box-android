package com.pandorasbox.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.fragment.app.FragmentActivity

class SplashActivity : FragmentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openHome = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(R.anim.splash_enter, R.anim.splash_exit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(openHome)

        val mark = findViewById<View>(R.id.splash_mark)
        val line = findViewById<View>(R.id.splash_accent_line)
        val title = findViewById<View>(R.id.splash_title)
        val subtitle = findViewById<View>(R.id.splash_subtitle)
        val motionEnabled = Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f

        if (motionEnabled) {
            val offset = 12f * resources.displayMetrics.density
            val easing = PathInterpolator(0.2f, 0f, 0.2f, 1f)

            mark.alpha = 0f
            mark.scaleX = 0.88f
            mark.scaleY = 0.88f
            mark.translationY = offset
            mark.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(520).setInterpolator(easing).withLayer().start()

            line.alpha = 1f
            line.pivotX = 0f
            line.scaleX = 0f
            line.animate().scaleX(1f).setStartDelay(260)
                .setDuration(490).setInterpolator(easing).start()

            title.alpha = 0f
            title.translationY = offset
            title.animate().alpha(1f).translationY(0f).setStartDelay(380)
                .setDuration(420).setInterpolator(easing).start()

            subtitle.alpha = 0f
            subtitle.translationY = offset
            subtitle.animate().alpha(1f).translationY(0f).setStartDelay(500)
                .setDuration(420).setInterpolator(easing).start()
        } else {
            mark.alpha = 1f
            line.scaleX = 1f
            title.alpha = 1f
            subtitle.alpha = 1f
        }

        handler.postDelayed(openHome, if (motionEnabled) 1250L else 120L)
    }

    override fun onPause() {
        handler.removeCallbacks(openHome)
        super.onPause()
    }
}
