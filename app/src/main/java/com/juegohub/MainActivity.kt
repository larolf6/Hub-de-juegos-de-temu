package com.juegohub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
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
    private var introUntil = 0L
    private var launching = false

    private val presets = listOf(
        intArrayOf(0xFF0E0E12.toInt(), 0xFF0E0E12.toInt()),
        intArrayOf(0xFF0B1026.toInt(), 0xFF1B2A6B.toInt()),
        intArrayOf(0xFF1A0B2E.toInt(), 0xFF4A1A7A.toInt()),
        intArrayOf(0xFF000814.toInt(), 0xFF3A0CA3.toInt()),
        intArrayOf(0xFF04140C.toInt(), 0xFF0F5132.toInt()),
        intArrayOf(0xFF1A0505.toInt(), 0xFF6B1515.toInt())
    )

    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }
    private val bgDir by lazy { File(filesDir, "bgs").also { it.mkdirs() } }

    private var iconDp: Int
        get() = prefs.getInt("iconDp", 64)
        set(v) { prefs.edit().putInt("iconDp", v).apply() }

    private var showNames: Boolean
        get() = prefs.getBoolean("showNames", true)
        set(v) { prefs.edit().putBoolean("showNames", v).apply() }

    // 0 = automatica, 1 = vertical, 2 = horizontal
    private var orient: Int
        get() = prefs.getInt("orient", 1)
        set(v) { prefs.edit().putInt("orient", v).apply() }

    // "p:<indice>" = degradado, "f:<archivo>" = imagen guardada
    private var bgKey: String
        get() = prefs.getString("bgKey", null)
            ?: (if (prefs.getInt("bgIndex", 0) == -1) "f:legacy.jpg" else "p:${prefs.getInt("bgIndex", 0)}")
        set(v) { prefs.edit().putString("bgKey", v).apply() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation()
        applyHighRefresh()
        migrateLegacyBackground()

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
            setOnItemClickListener { _, view, i, _ ->
                launchWithAnim(view, games[i].pkg)
            }
            setOnItemLongClickListener { _, _, i, _ ->
                showOptions(games[i])
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
        playIntro(bar)
        reload()
    }

    override fun onResume() {
        super.onResume()
        launching = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.requestLayout()
    }

    // ---------- pantalla / orientacion / hz ----------

    private fun applyOrientation() {
        requestedOrientation = when (orient) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Suppress("DEPRECATION")
    private fun applyHighRefresh() {
        try {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter {
                    it.physicalWidth == cur.physicalWidth &&
                        it.physicalHeight == cur.physicalHeight &&
                        it.refreshRate <= 145f
                }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (e: Exception) {
            // si el telefono no lo permite, se ignora
        }
    }

    // ---------- animaciones ----------

    private fun playIntro(bar: View) {
        introUntil = SystemClock.uptimeMillis() + 1800
        bgView.alpha = 0f
        bgView.animate().alpha(1f).setDuration(600).start()
        bar.alpha = 0f
        bar.translationY = -dp(16).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun launchWithAnim(v: View, pkg: String) {
        if (launching) return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launching = true
        v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction {
            v.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setDuration(140).withEndAction {
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                v.scaleX = 1f
                v.scaleY = 1f
                v.alpha = 1f
            }.start()
        }.start()
    }

    // ---------- fondo ----------

    private fun migrateLegacyBackground() {
        val old = File(filesDir, "bg.jpg")
        if (old.exists()) old.renameTo(File(bgDir, "legacy.jpg"))
    }

    private fun bgFiles(): List<File> =
        (bgDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".jpg") && !it.name.endsWith("_t.jpg") }
            .sortedBy { it.lastModified() }

    private fun thumbFile(f: File) = File(bgDir, f.name.removeSuffix(".jpg") + "_t.jpg")

    private fun applyBackground() {
        val key = bgKey
        if (key.startsWith("f:")) {
            val f = File(bgDir, key.substring(2))
            if (f.exists()) {
                bgView.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                dim.visibility = View.VISIBLE
                return
            }
        }
        val i = key.removePrefix("p:").toIntOrNull() ?: 0
        val c = presets[if (i in presets.indices) i else 0]
        bgView.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c))
        dim.visibility = View.GONE
    }

    private fun applyGrid() {
        grid.columnWidth = dp(iconDp + 24)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    // ---------- datos ----------

    private fun saved(): Set<String> =
        prefs.getStringSet("games", emptySet()) ?: emptySet()

    private fun save(s: Set<String>) {
        prefs.edit().putStringSet("games", HashSet(s)).apply()
    }

    private fun favs(): Set<String> =
        prefs.getStringSet("favs", emptySet()) ?: emptySet()

    private fun saveFavs(s: Set<String>) {
        prefs.edit().putStringSet("favs", HashSet(s)).apply()
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
        val f = favs()
        games.sortWith(compareByDescending<Item> { f.contains(it.pkg) }.thenBy { it.label.lowercase() })
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- ajustes ----------

    private fun ring(selected: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(Color.TRANSPARENT)
        d.setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else 0x55FFFFFF)
        return d
    }

    private fun showSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(8))

        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(16), 0, dp(6))
        }

        // tamano de iconos
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

        // nombres
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

        // orientacion
        col.addView(label("Orientación"))
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Auto", "Vertical", "Horizontal")
        fun buildSeg() {
            seg.removeAllViews()
            names.forEachIndexed { i, n ->
                val sel = orient == i
                val t = TextView(this)
                t.text = n
                t.gravity = Gravity.CENTER
                t.textSize = 14f
                t.setPadding(0, dp(10), 0, dp(10))
                t.setTextColor(if (sel) Color.WHITE else Color.parseColor("#B0B0B8"))
                val b = GradientDrawable()
                b.cornerRadius = dp(10).toFloat()
                b.setColor(if (sel) Color.parseColor("#3D5AFE") else Color.parseColor("#2A2A30"))
                t.background = b
                t.setOnClickListener {
                    orient = i
                    applyOrientation()
                    buildSeg()
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.rightMargin = dp(6)
                seg.addView(t, lp)
            }
        }
        buildSeg()
        col.addView(seg)

        // fondos
        col.addView(label("Fondo (mantén pulsado un fondo guardado para borrarlo)"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun buildSwatches() {
            row.removeAllViews()
            val current = bgKey

            presets.forEachIndexed { i, c ->
                val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c)
                d.shape = GradientDrawable.OVAL
                val v = View(this)
                v.background = d
                v.foreground = ring(current == "p:$i")
                v.setOnClickListener {
                    bgKey = "p:$i"
                    applyBackground()
                    buildSwatches()
                }
                val lp = LinearLayout.LayoutParams(dp(44), dp(44))
                lp.rightMargin = dp(10)
                row.addView(v, lp)
            }

            for (f in bgFiles()) {
                val tf = thumbFile(f)
                val bmp = if (tf.exists()) {
                    BitmapFactory.decodeFile(tf.absolutePath)
                } else {
                    val o = BitmapFactory.Options()
                    o.inSampleSize = 8
                    BitmapFactory.decodeFile(f.absolutePath, o)
                }
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(bmp)
                iv.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                iv.clipToOutline = true
                iv.foreground = ring(current == "f:${f.name}")
                iv.setOnClickListener {
                    bgKey = "f:${f.name}"
                    applyBackground()
                    buildSwatches()
                }
                iv.setOnLongClickListener {
                    confirmDeleteBackground(f)
                    true
                }
                val lp = LinearLayout.LayoutParams(dp(44), dp(44))
                lp.rightMargin = dp(10)
                row.addView(iv, lp)
            }

            val plus = TextView(this)
            plus.text = "+"
            plus.gravity = Gravity.CENTER
            plus.textSize = 24f
            plus.setTextColor(Color.WHITE)
            plus.background = ring(false)
            plus.setOnClickListener { pickImage() }
            row.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        buildSwatches()
        swatchRefresh = { buildSwatches() }

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        col.addView(hs)

        // creditos
        val credits = TextView(this)
        credits.text = "MyGameHub v1.2\nCreado por larolf6\nProgramado con ayuda de Claude (Anthropic)"
        credits.setTextColor(Color.parseColor("#8E8E93"))
        credits.textSize = 12f
        credits.gravity = Gravity.CENTER
        credits.setPadding(0, dp(28), 0, dp(8))
        col.addView(credits, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        scroll.addView(col)

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Ajustes")
            .setView(scroll)
            .setPositiveButton("Listo", null)
            .setOnDismissListener { swatchRefresh = null }
            .show()
    }

    private fun confirmDeleteBackground(f: File) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("¿Borrar este fondo?")
            .setPositiveButton("Borrar") { _, _ ->
                if (bgKey == "f:${f.name}") {
                    bgKey = "p:0"
                    applyBackground()
                }
                f.delete()
                thumbFile(f).delete()
                swatchRefresh?.invoke()
            }
            .setNegativeButton("Cancelar", null)
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

            val name = "bg_${System.currentTimeMillis()}.jpg"
            val f = File(bgDir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }

            val scale = 200f / maxOf(bmp.width, bmp.height)
            val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val th = (bmp.height * scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
            FileOutputStream(thumbFile(f)).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }

            bgKey = "f:$name"
            applyBackground()
            swatchRefresh?.invoke()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- selector de apps ----------

    private fun showPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val favsInit = favs()
        val all = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { Item(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(compareByDescending<Item> { favsInit.contains(it.pkg) }.thenBy { it.label.lowercase() })

        val chosen = saved().toMutableSet()
        val fav = favs().toMutableSet()
        var shown: List<Item> = all

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), 0)

        val search = EditText(this)
        search.hint = "Buscar por nombre..."
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(Color.parseColor("#8E8E93"))
        search.setSingleLine(true)
        col.addView(search, LinearLayout.LayoutParams(-1, -2))

        val list = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()

            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val app = shown[i]
                val ctx = this@MainActivity
                val rowView = LinearLayout(ctx)
                rowView.orientation = LinearLayout.HORIZONTAL
                rowView.gravity = Gravity.CENTER_VERTICAL
                rowView.setPadding(0, dp(6), 0, dp(6))

                val cb = CheckBox(ctx)
                cb.isChecked = chosen.contains(app.pkg)
                cb.isClickable = false
                cb.isFocusable = false
                rowView.addView(cb)

                val ic = ImageView(ctx)
                try {
                    ic.setImageDrawable(packageManager.getApplicationIcon(app.pkg))
                } catch (e: Exception) {
                    // sin icono
                }
                val iclp = LinearLayout.LayoutParams(dp(40), dp(40))
                iclp.leftMargin = dp(4)
                rowView.addView(ic, iclp)

                val name = TextView(ctx)
                name.text = app.label
                name.setTextColor(Color.WHITE)
                name.textSize = 15f
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.END
                name.setPadding(dp(12), 0, dp(8), 0)
                rowView.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val isFav = fav.contains(app.pkg)
                val star = TextView(ctx)
                star.text = if (isFav) "★" else "☆"
                star.textSize = 24f
                star.setTextColor(if (isFav) Color.parseColor("#FFC93C") else Color.parseColor("#8E8E93"))
                star.setPadding(dp(12), dp(4), dp(4), dp(4))
                star.setOnClickListener {
                    if (fav.contains(app.pkg)) {
                        fav.remove(app.pkg)
                    } else {
                        fav.add(app.pkg)
                        chosen.add(app.pkg)
                    }
                    notifyDataSetChanged()
                }
                rowView.addView(star)
                return rowView
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            val app = shown[i]
            if (!chosen.add(app.pkg)) chosen.remove(app.pkg)
            adapter.notifyDataSetChanged()
        }
        val listHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
        col.addView(list, LinearLayout.LayoutParams(-1, listHeight))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Elige tus juegos")
            .setView(col)
            .setPositiveButton("Guardar") { _, _ ->
                save(chosen)
                saveFavs(fav.intersect(chosen))
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    // ---------- opciones de un juego ----------

    private fun showOptions(item: Item) {
        val isFav = favs().contains(item.pkg)
        val opts = arrayOf(
            if (isFav) "Quitar de favoritos" else "Marcar como favorito",
            "Quitar de la lista"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(item.label)
            .setItems(opts) { _, which ->
                if (which == 0) {
                    val s = favs().toMutableSet()
                    if (isFav) s.remove(item.pkg) else s.add(item.pkg)
                    saveFavs(s)
                    reload()
                } else {
                    confirmRemove(item)
                }
            }
            .show()
    }

    private fun confirmRemove(item: Item) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("¿Quitar ${item.label}?")
            .setMessage("Solo se quita de esta lista, no se desinstala.")
            .setPositiveButton("Quitar") { _, _ ->
                val s = saved().toMutableSet()
                s.remove(item.pkg)
                save(s)
                val f = favs().toMutableSet()
                f.remove(item.pkg)
                saveFavs(f)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- cuadricula ----------

    inner class GameAdapter : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(i: Int) = games[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
            val item = games[i]
            val ctx = this@MainActivity
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(dp(6), dp(12), dp(6), dp(12))

            val iconWrap = FrameLayout(ctx)
            val icon = ImageView(ctx)
            try {
                icon.setImageDrawable(packageManager.getApplicationIcon(item.pkg))
            } catch (e: Exception) {
                // sin icono
            }
            iconWrap.addView(icon, FrameLayout.LayoutParams(-1, -1))
            if (favs().contains(item.pkg)) {
                val star = TextView(ctx)
                star.text = "★"
                star.textSize = 14f
                star.setTextColor(Color.parseColor("#FFC93C"))
                star.setShadowLayer(4f, 0f, 1f, Color.BLACK)
                val slp = FrameLayout.LayoutParams(-2, -2)
                slp.gravity = Gravity.TOP or Gravity.END
                iconWrap.addView(star, slp)
            }
            box.addView(iconWrap, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))

            if (showNames) {
                val name = TextView(ctx)
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

            if (SystemClock.uptimeMillis() < introUntil) {
                box.alpha = 0f
                box.scaleX = 0.7f
                box.scaleY = 0.7f
                box.translationY = dp(24).toFloat()
                box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(minOf(i, 24) * 35L + 150)
                    .setDuration(380)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            return box
        }
    }
}
