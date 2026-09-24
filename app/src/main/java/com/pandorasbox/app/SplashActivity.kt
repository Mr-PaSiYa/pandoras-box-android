package com.pandorasbox.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.LottieAnimationView

class SplashActivity : FragmentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logo: LottieAnimationView
    private lateinit var progressLine: View
    private var progressAnimator: ObjectAnimator? = null
    private var started = false
    private var foreground = false
    private var homeOpened = false

    private val openHome = Runnable {
        if (!foreground || homeOpened) return@Runnable
        homeOpened = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(R.anim.splash_enter, R.anim.splash_exit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        PythonRuntime.prepare(applicationContext)

        logo = findViewById(R.id.splash_logo)
        progressLine = findViewById(R.id.splash_progress)
        progressLine.pivotX = 0f
        progressLine.scaleX = 0f

        logo.setFontMap(
            mapOf(
                "Titillium Web Extra Light" to Typeface.createFromAsset(
                    assets, "splash_fonts/Titillium Web Extra Light.ttf"
                ),
                "Titillium Web Semi Bold" to Typeface.createFromAsset(
                    assets, "splash_fonts/Titillium Web Semi Bold.ttf"
                )
            )
        )
        // The visible artwork finishes at frame 70; the source has a long static tail.
        logo.setMaxFrame(70)
        logo.speed = 1.1f
        logo.addAnimatorListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                progressAnimator?.end()
                if (foreground) handler.postDelayed(openHome, 120L)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        val motionEnabled = Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f

        if (!motionEnabled) {
            logo.progress = 70f / 150f
            progressLine.scaleX = 1f
            handler.postDelayed(openHome, 120L)
            return
        }

        if (!started) {
            started = true
            progressAnimator = ObjectAnimator.ofFloat(progressLine, View.SCALE_X, 0f, 1f).apply {
                duration = 2120L
                interpolator = LinearInterpolator()
                start()
            }
            logo.playAnimation()
        } else if (logo.progress >= 70f / 150f) {
            handler.postDelayed(openHome, 120L)
        } else {
            progressAnimator?.resume()
            logo.resumeAnimation()
        }
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(openHome)
        progressAnimator?.pause()
        logo.pauseAnimation()
        super.onPause()
    }
}
