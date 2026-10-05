package com.shehab360.videoplayerpro

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.shehab360.videoplayerpro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var appliedThemeVersion = 0

    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appliedThemeVersion = ThemeManager.version

        // edge-to-edge: status bar রং জোর করে বসানো হয় না; জায়গা ছাড়া + আইকনের রং থিম অনুযায়ী
        binding.contentRoot.applySystemBarsPadding()
        ThemeManager.styleSystemBars(this)

        setupToolbar()
        setupDrawer()
        setupTabs()

        onBackPressedDispatcher.addCallback(this, drawerBackCallback)
    }

    override fun onResume() {
        super.onResume()
        // Settings এ থিম/ডায়নামিক কালার বদলালে এখানে ফিরে নিজেকে নতুন করে আঁকা
        if (appliedThemeVersion != ThemeManager.version) recreate()
        refreshToolbarState()
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------
    private fun setupToolbar() {
        val toolbar = binding.topAppBar
        toolbar.setNavigationOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                sendQuery(query.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                sendQuery(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                sendQuery("")
                return true
            }
        })

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_toggle -> {
                    AppPrefs.gridView = !AppPrefs.gridView
                    refreshToolbarState()
                    sendRefresh()
                    true
                }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_folder_view -> {
                    AppPrefs.folderView = !AppPrefs.folderView
                    item.isChecked = AppPrefs.folderView
                    sendRefresh()
                    true
                }
                R.id.action_theme -> { showThemeDialog(); true }
                else -> false
            }
        }
    }

    private fun refreshToolbarState() {
        val menu: Menu = binding.topAppBar.menu
        menu.findItem(R.id.action_view_toggle)?.apply {
            setIcon(if (AppPrefs.gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        }
        menu.findItem(R.id.action_folder_view)?.isChecked = AppPrefs.folderView
        // সর্ট / গ্রিড / ফোল্ডার শুধু ভিডিও ট্যাবের জন্য
        val onVideos = binding.viewPager.currentItem == 0
        menu.findItem(R.id.action_view_toggle)?.isVisible = onVideos
        menu.findItem(R.id.action_sort)?.isVisible = onVideos
        menu.findItem(R.id.action_folder_view)?.isVisible = onVideos
    }

    private fun sendQuery(q: String) {
        supportFragmentManager.setFragmentResult(VideosFragment.REQ_QUERY, bundleOf(VideosFragment.KEY_QUERY to q))
    }

    private fun sendRefresh() {
        supportFragmentManager.setFragmentResult(VideosFragment.REQ_REFRESH, bundleOf())
    }

    private fun showSortDialog() {
        val labels = resources.getStringArray(R.array.sort_options)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(labels, AppPrefs.sortIndex) { d, which ->
                AppPrefs.sortIndex = which
                sendRefresh()
                d.dismiss()
            }
            .show()
    }

    private fun showThemeDialog() {
        val labels = resources.getStringArray(R.array.theme_options)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, AppPrefs.themeMode) { d, which ->
                AppPrefs.themeMode = which
                ThemeManager.applyGlobal()
                ThemeManager.version++
                d.dismiss()
                recreate()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Drawer
    // ------------------------------------------------------------------
    private fun setupDrawer() {
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) { drawerBackCallback.isEnabled = true }
            override fun onDrawerClosed(drawerView: android.view.View) { drawerBackCallback.isEnabled = false }
        })

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_local_files -> binding.viewPager.currentItem = 0
                R.id.nav_history -> startActivity(Intent(this, HistoryActivity::class.java))
                R.id.nav_stream -> showStreamDialog()
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_privacy -> showInfoDialog(R.string.privacy_policy, R.string.privacy_policy_text)
                R.id.nav_terms -> showInfoDialog(R.string.terms_of_use, R.string.terms_text)
                R.id.nav_contact -> contactUs()
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showInfoDialog(title: Int, message: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** মেইল অ্যাপ না থাকলে ক্র্যাশ নয়, বার্তা */
    private fun contactUs() {
        val intent = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:shehab360@gmail.com") }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_mail_app, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Network stream (URL / HLS / DASH)
    // ------------------------------------------------------------------
    private fun showStreamDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_stream, null)
        val input = view.findViewById<TextInputEditText>(R.id.etStreamUrl)

        // ক্লিপবোর্ডে লিংক থাকলে আগে থেকে বসানো
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.startsWith("http", ignoreCase = true)) input.setText(text)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_stream)
            .setView(view)
            .setPositiveButton(R.string.play) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (Patterns.WEB_URL.matcher(url).matches() && url.startsWith("http", ignoreCase = true)) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_VIDEO_URI, url)
                            .putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, url.substringBefore('?').substringAfterLast('/').ifEmpty { url })
                    )
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------
    private fun setupTabs() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(if (position == 0) R.string.tab_videos else R.string.tab_music)
        }.attach()
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = refreshToolbarState()
        })
    }
}
