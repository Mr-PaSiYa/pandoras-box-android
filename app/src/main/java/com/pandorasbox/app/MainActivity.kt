package com.pandorasbox.app

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import androidx.core.view.isVisible

class MainActivity : FragmentActivity() {
    private lateinit var bottomNav: LinearLayout
    private lateinit var pager: ViewPager2
    private val tabs = intArrayOf(R.id.nav_download, R.id.nav_queue, R.id.nav_history)
    private var keyboardVisible = false

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
        currentNavId = savedInstanceState?.getInt("current_nav_id", R.id.nav_download) ?: R.id.nav_download
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

        bottomNav = findViewById(R.id.bottom_nav)
        pager = findViewById(R.id.main_pager)
        val root = findViewById<View>(R.id.activity_root)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleArea = Rect()
            root.getWindowVisibleDisplayFrame(visibleArea)
            val keyboardOpen = root.rootView.height - visibleArea.bottom >
                160f * resources.displayMetrics.density
            if (keyboardVisible != keyboardOpen) {
                keyboardVisible = keyboardOpen
                updateSettingsVisibility()
            }
        }
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabs.size

            override fun createFragment(position: Int): Fragment = when (position) {
                1 -> QueueFragment()
                2 -> HistoryFragment()
                else -> DownloadFragment()
            }
        }
        pager.offscreenPageLimit = 2
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        pager.setCurrentItem(tabs.indexOf(currentNavId).coerceAtLeast(0), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                updateNavProgress(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentNavId = tabs[position]
                updateSelectedLabel()
            }
        })

        tabs.forEach { id -> findViewById<View>(id).setOnClickListener { selectTab(id) } }
        updateSelectedLabel()
        updateNavProgress(tabs.indexOf(currentNavId).toFloat())
        supportFragmentManager.addOnBackStackChangedListener { updateSettingsVisibility() }
        updateSettingsVisibility()
    }

    private fun selectTab(id: Int) {
        if (id == currentNavId || supportFragmentManager.backStackEntryCount != 0) return
        pager.setCurrentItem(tabs.indexOf(id), motionEnabled())
    }

    private fun updateSettingsVisibility() {
        val settingsOpen = supportFragmentManager.backStackEntryCount != 0
        bottomNav.isVisible = !settingsOpen && !keyboardVisible
        pager.isUserInputEnabled = !settingsOpen && !keyboardVisible
    }

    private fun updateSelectedLabel() {
        val labels = intArrayOf(R.id.nav_label_home, R.id.nav_label_queue, R.id.nav_label_library)
        tabs.indices.forEach { i ->
            findViewById<TextView>(labels[i]).apply {
                setTypeface(typeface, if (tabs[i] == currentNavId) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun updateNavProgress(progress: Float) {
        val icons = intArrayOf(R.id.nav_icon_home, R.id.nav_icon_queue, R.id.nav_icon_library)
        val labels = intArrayOf(R.id.nav_label_home, R.id.nav_label_queue, R.id.nav_label_library)
        val indicators = intArrayOf(R.id.nav_indicator_home, R.id.nav_indicator_queue, R.id.nav_indicator_library)
        val activeColor = ContextCompat.getColor(this, R.color.nav_item_selected)
        val idleColor = ContextCompat.getColor(this, R.color.nav_item_unselected)
        tabs.indices.forEach { i ->
            val strength = (1f - kotlin.math.abs(i - progress)).coerceIn(0f, 1f)
            val color = ColorUtils.blendARGB(idleColor, activeColor, strength)
            findViewById<ImageView>(icons[i]).apply {
                imageTintList = ColorStateList.valueOf(color)
                scaleX = 0.96f + strength * 0.04f
                scaleY = 0.96f + strength * 0.04f
            }
            findViewById<TextView>(labels[i]).setTextColor(color)
            findViewById<View>(indicators[i]).alpha = strength
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("current_nav_id", currentNavId)
        super.onSaveInstanceState(outState)
    }

    fun openSettings() {
        if (supportFragmentManager.backStackEntryCount != 0) return
        val transaction = supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
        if (motionEnabled()) transaction.setCustomAnimations(
            R.anim.slide_in_right, R.anim.slide_out_left,
            R.anim.slide_in_left, R.anim.slide_out_right
        )
        transaction
            .replace(R.id.settings_container, SettingsFragment())
            .addToBackStack("settings")
            .commit()
    }

    fun closeSettings() {
        supportFragmentManager.popBackStack()
    }

    private fun motionEnabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

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
