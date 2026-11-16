package com.juegohub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    data class Item(val pkg: String, val label: String)

    private val games = mutableListOf<Item>()
    private lateinit var grid: GridView
    private lateinit var empty: TextView

    private val bg = Color.parseColor("#121214")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val title = TextView(this).apply {
            text = "Mis Juegos"
            setTextColor(Color.WHITE)
            textSize = 24f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = TextView(this).apply {
            text = "+ Agregar"
            setTextColor(Color.parseColor("#7CC4FF"))
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showPicker() }
        }
        bar.addView(add)
        root.addView(bar)

        grid = GridView(this).apply {
            numColumns = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = GameAdapter()
            setOnItemClickListener { _, _, i, _ ->
                packageManager.getLaunchIntentForPackage(games[i].pkg)?.let { startActivity(it) }
            }
            setOnItemLongClickListener { _, _, i, _ ->
                confirmRemove(games[i])
                true
            }
        }
        empty = TextView(this).apply {
            text = "Toca \"+ Agregar\" para elegir tus juegos"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(this)
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        reload()
    }

    private fun saved(): Set<String> =
        getSharedPreferences("hub", MODE_PRIVATE).getStringSet("games", emptySet()) ?: emptySet()

    private fun save(s: Set<String>) {
        getSharedPreferences("hub", MODE_PRIVATE).edit().putStringSet("games", HashSet(s)).apply()
    }

    private fun reload() {
        games.clear()
        for (p in saved()) {
            try {
                val info = packageManager.getApplicationInfo(p, 0)
                games.add(Item(p, packageManager.getApplicationLabel(info).toString()))
            } catch (e: PackageManager.NameNotFoundException) {
                // app desinstalada: se omite
            }
        }
        games.sortBy { it.label.lowercase() }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { Item(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }

        val current = saved().toMutableSet()
        val checked = BooleanArray(apps.size) { current.contains(apps[it].pkg) }

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Elige tus juegos")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton("Guardar") { _, _ ->
                val chosen = mutableSetOf<String>()
                apps.forEachIndexed { i, a -> if (checked[i]) chosen.add(a.pkg) }
                save(chosen)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmRemove(item: Item) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Quitar ${item.label}?")
            .setMessage("Solo se quita de esta lista, no se desinstala.")
            .setPositiveButton("Quitar") { _, _ ->
                val s = saved().toMutableSet()
                s.remove(item.pkg)
                save(s)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class GameAdapter : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(i: Int) = games[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
            val item = games[i]
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(12), dp(6), dp(12))
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(packageManager.getApplicationIcon(item.pkg))
            }
            box.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
            val name = TextView(this@MainActivity).apply {
                text = item.label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(name, LinearLayout.LayoutParams(-1, -2))
            return box
        }
    }
}
