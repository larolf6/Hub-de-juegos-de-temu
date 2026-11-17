package com.juegohub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    data class Item(val pkg: String, val label: String)

    private val games = mutableListOf<Item>()
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var bgView: ImageView
    private lateinit var dim: View
    private var swatchRefresh: (() -> Unit)? = null

    private val presets = listOf(
        intArrayOf(0xFF0E0E12.toInt(), 0xFF0E0E12.toInt()),
        intArrayOf(0xFF0B1026.toInt(), 0xFF1B2A6B.toInt()),
        intArrayOf(0xFF1A0B2E.toInt(), 0xFF4A1A7A.toInt()),
        intArrayOf(0xFF000814.toInt(), 0xFF3A0CA3.toInt()),
        intArrayOf(0xFF04140C.toInt(), 0xFF0F5132.toInt()),
        intArrayOf(0xFF1A0505.toInt(), 0xFF6B1515.toInt())
    )

    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }

    private var iconDp: Int
        get() = prefs.getInt("iconDp", 64)
        set(v) { prefs.edit().putInt("iconDp", v).apply() }

    private var showNames: Boolean
        get() = prefs.getBoolean("showNames", true)
        set(v) { prefs.edit().putBoolean("showNames", v).apply() }

    private var bgIndex: Int
        get() = prefs.getInt("bgIndex", 0)
        set(v) { prefs.edit().putInt("bgIndex", v).apply() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this)

        bgView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))
        dim = View(this).apply { setBackgroundColor(0x66000000) }
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(8))
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
        val gear = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showSettings() }
        }
        bar.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(bar)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
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
            setTextColor(Color.parseColor("#B0B0B8"))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(this)
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        applyBackground()
        applyGrid()
        reload()
    }

    private fun applyBackground() {
        val idx = bgIndex
        val f = File(filesDir, "bg.jpg")
        if (idx == -1 && f.exists()) {
            bgView.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            dim.visibility = View.VISIBLE
        } else {
            val c = presets[if (idx in presets.indices) idx else 0]
            bgView.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c))
            dim.visibility = View.GONE
        }
    }

    private fun applyGrid() {
        grid.columnWidth = dp(iconDp + 24)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun saved(): Set<String> =
        prefs.getStringSet("games", emptySet()) ?: emptySet()

    private fun save(s: Set<String>) {
        prefs.edit().putStringSet("games", HashSet(s)).apply()
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

    private fun showSettings() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(16), 0, dp(6))
        }

        col.addView(label("Tamaño de los iconos"))
        val seek = SeekBar(this)
        seek.max = 72
        seek.progress = iconDp - 40
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                iconDp = 40 + p
                applyGrid()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(seek)

        val sw = Switch(this)
        sw.text = "Mostrar nombres"
        sw.setTextColor(Color.WHITE)
        sw.textSize = 15f
        sw.isChecked = showNames
        sw.setPadding(0, dp(16), 0, dp(8))
        sw.setOnCheckedChangeListener { _, on ->
            showNames = on
            applyGrid()
        }
        col.addView(sw)

        col.addView(label("Fondo"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun buildSwatches() {
            row.removeAllViews()
            presets.forEachIndexed { i, c ->
                val selected = bgIndex == i
                val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c)
                d.shape = GradientDrawable.OVAL
                d.setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else 0x55FFFFFF)
                val v = View(this)
                v.background = d
                v.setOnClickListener {
                    bgIndex = i
                    applyBackground()
                    buildSwatches()
                }
                val lp = LinearLayout.LayoutParams(dp(40), dp(40))
                lp.rightMargin = dp(10)
                row.addView(v, lp)
            }
        }
        buildSwatches()
        swatchRefresh = { buildSwatches() }

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        col.addView(scroll)

        val pick = TextView(this)
        pick.text = "Elegir imagen de la galería"
        pick.setTextColor(Color.parseColor("#7CC4FF"))
        pick.textSize = 15f
        pick.setPadding(0, dp(16), 0, dp(8))
        pick.setOnClickListener { pickImage() }
        col.addView(pick)

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Ajustes")
            .setView(col)
            .setPositiveButton("Listo", null)
            .setOnDismissListener { swatchRefresh = null }
            .show()
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        startActivityForResult(Intent.createChooser(i, "Elegir fondo"), 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return
            FileOutputStream(File(filesDir, "bg.jpg")).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            bgIndex = -1
            applyBackground()
            swatchRefresh?.invoke()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
        }
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
            val box = LinearLayout(this@MainActivity)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(dp(6), dp(12), dp(6), dp(12))

            val icon = ImageView(this@MainActivity)
            icon.setImageDrawable(packageManager.getApplicationIcon(item.pkg))
            box.addView(icon, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))

            if (showNames) {
                val name = TextView(this@MainActivity)
                name.text = item.label
                name.setTextColor(Color.WHITE)
                name.setShadowLayer(4f, 0f, 1f, Color.BLACK)
                name.textSize = 12f
                name.gravity = Gravity.CENTER
                name.maxLines = 2
                name.ellipsize = TextUtils.TruncateAt.END
                name.setPadding(0, dp(6), 0, 0)
                box.addView(name, LinearLayout.LayoutParams(-1, -2))
            }
            return box
        }
    }
}
