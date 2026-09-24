package com.pandorasbox.app

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : FragmentActivity() {

    // Android 13+ shows a "Allow notifications?" dialog. Nothing extra to do with the answer:
    // DownloadNotifier checks the permission every time before it posts.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** Handles GitHub update checks/dialogs. SettingsFragment uses it for "Check for updates". */
    lateinit var updateFlow: UpdateFlow
        private set

    private var currentNavId: Int = R.id.nav_download

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start Python if it isn't already running
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        FFmpegHelper.prepareFFmpeg(applicationContext)
        DownloadManager.init(applicationContext)
        askNotificationPermissionOnce()

        // Non-blocking: runs on a background thread and only shows a dialog if a newer release exists.
        // Skipped on rotation/restore (savedInstanceState != null) so the dialog isn't repeated.
        updateFlow = UpdateFlow(this)
        if (savedInstanceState == null) {
            updateFlow.check(manual = false)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                .replace(R.id.fragment_container, DownloadFragment())
                .commit()
            currentNavId = R.id.nav_download
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true

            val selectedFragment: Fragment = when (item.itemId) {
                R.id.nav_download -> DownloadFragment()
                R.id.nav_queue -> QueueFragment()
                R.id.nav_history -> HistoryFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> DownloadFragment()
            }

            // Direction-aware: moving right → slide from right, moving left → soft fade
            val goingForward = navOrder(item.itemId) > navOrder(currentNavId)

            val enterAnim = if (goingForward) R.anim.slide_in_right else R.anim.fade_in
            val exitAnim  = if (goingForward) R.anim.slide_out_left else R.anim.fade_out

            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(enterAnim, exitAnim)
                .replace(R.id.fragment_container, selectedFragment)
                .commit()

            currentNavId = item.itemId
            true
        }
    }

    /** Simple left-to-right order of the bottom nav items for direction-aware transitions. */
    private fun navOrder(itemId: Int): Int = when (itemId) {
        R.id.nav_download -> 0
        R.id.nav_queue -> 1
        R.id.nav_history -> 2
        R.id.nav_settings -> 3
        else -> 0
    }

    /**
     * Android 13+ needs the user's permission to show notifications. Ask once, on first launch,
     * and only if the notification setting is on. Later, the Settings toggle asks again when
     * the user switches it on.
     */
    private fun askNotificationPermissionOnce() {
        if (!DownloadManager.settings.value.notificationsEnabled) return
        if (!DownloadNotifier.needsPermission(this)) return

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (prefs.getBoolean("notif_permission_asked", false)) return
        prefs.edit().putBoolean("notif_permission_asked", true).apply()

        notificationPermissionLauncher.launch(DownloadNotifier.PERMISSION)
    }
}
