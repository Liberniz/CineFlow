package com.cineflow.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cineflow.tv.ui.BrowseFragment
import com.cineflow.tv.ui.FavoritesFragment
import com.cineflow.tv.ui.Graph
import com.cineflow.tv.ui.HomeFragment
import com.cineflow.tv.ui.Images
import com.cineflow.tv.ui.SearchFragment
import com.cineflow.tv.ui.SettingsFragment

/**
 * Native TV shell (v5.0): a left nav rail + fragment content area.
 * Replaces the old WebView host — the whole UI is native Views now,
 * only the data layer (Store / TmdbClient / ResourceClient) and the
 * ExoPlayer screen are carried over from the WebView era.
 */
class MainActivity : AppCompatActivity() {

    private val fragments = HashMap<String, Fragment>()
    private var currentTag: String = "home"
    private lateinit var navItems: Map<String, TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.ensure(this)
        Images.applyLoader(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_main)

        navItems = mapOf(
            "home" to findViewById(R.id.navHome),
            "browse" to findViewById(R.id.navBrowse),
            "search" to findViewById(R.id.navSearch),
            "fav" to findViewById(R.id.navFav),
            "settings" to findViewById(R.id.navSettings)
        )
        navItems.forEach { (tag, view) ->
            view.setOnClickListener { showTag(tag) }
        }
        showTag("home")
        window.decorView.post { navItems["home"]?.requestFocus() }
    }

    private fun showTag(tag: String) {
        currentTag = tag
        navItems.forEach { (t, v) -> v.isSelected = t == tag }
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fragments.values.forEach { tx.hide(it) }
        val existing = fragments[tag] ?: fm.findFragmentByTag(tag)
        val f: Fragment = if (existing == null) {
            val created = when (tag) {
                "browse" -> BrowseFragment()
                "search" -> SearchFragment()
                "fav" -> FavoritesFragment()
                "settings" -> SettingsFragment()
                else -> HomeFragment()
            }
            tx.add(R.id.contentContainer, created, tag)
            created
        } else {
            tx.show(existing)
            existing
        }
        fragments[tag] = f
        tx.commit()
    }

    // Remote Back: any section other than Home returns to Home first.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && currentTag != "home") {
            showTag("home")
            navItems["home"]?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
