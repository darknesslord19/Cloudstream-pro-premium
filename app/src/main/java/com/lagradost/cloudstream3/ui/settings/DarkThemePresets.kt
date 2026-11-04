package com.lagradost.cloudstream3.ui.settings

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * CloudStream Dark: 50 full dark themes. Each one is a style overlay
 * (res/values/themes_dark_presets.xml, generated together with the list
 * below) that sets the backgrounds, card colour, text and the accent
 * (colorPrimary) for the whole app. Applied after the app theme and the
 * primary colour in CommonActivity.loadThemes; empty = off.
 */
object DarkThemePresets {
    const val PREF_KEY = "dark_theme_preset"

    data class Preset(
        val key: String,
        val name: String,
        val group: String,
        val bg: Int,
        val surface: Int,
        val accent: Int,
    )

    val all: List<Preset> = listOf(
        Preset("DarknesAltin", "Darknes Altın", "Klasik", 0xFF0B0B0B.toInt(), 0xFF1C1C1E.toInt(), 0xFFF5C542.toInt()),
        Preset("Monokrom", "Monokrom", "Klasik", 0xFF000000.toInt(), 0xFF161616.toInt(), 0xFFE6E6E6.toInt()),
        Preset("Grafit", "Grafit", "Klasik", 0xFF121212.toInt(), 0xFF1E1E1E.toInt(), 0xFFBDBDBD.toInt()),
        Preset("SinemaKirmizisi", "Sinema Kırmızısı", "Klasik", 0xFF111111.toInt(), 0xFF1D1D1D.toInt(), 0xFFE50914.toInt()),
        Preset("GeceYarisiMavisi", "Gece Yarısı Mavisi", "Klasik", 0xFF070B1A.toInt(), 0xFF10172E.toInt(), 0xFF3D7BFF.toInt()),
        Preset("Celik", "Çelik", "Klasik", 0xFF101317.toInt(), 0xFF1B2026.toInt(), 0xFF90A4AE.toInt()),
        Preset("Sis", "Sis", "Klasik", 0xFF14161A.toInt(), 0xFF20242B.toInt(), 0xFFB0BEC5.toInt()),
        Preset("Kraliyet", "Kraliyet", "Klasik", 0xFF0E0B1A.toInt(), 0xFF1A1530.toInt(), 0xFFFFD166.toInt()),
        Preset("GokGurultusu", "Gök Gürültüsü", "Klasik", 0xFF0E1218.toInt(), 0xFF18202B.toInt(), 0xFFFFC400.toInt()),
        Preset("Murekkep", "Mürekkep", "Klasik", 0xFF0A0A12.toInt(), 0xFF14141F.toInt(), 0xFF6C63FF.toInt()),
        Preset("OkyanusDerinligi", "Okyanus Derinliği", "Doğa", 0xFF071218.toInt(), 0xFF0F2430.toInt(), 0xFF2EC4E6.toInt()),
        Preset("Orman", "Orman", "Doğa", 0xFF0D1A12.toInt(), 0xFF16291D.toInt(), 0xFF4CAF50.toInt()),
        Preset("Everforest", "Everforest", "Doğa", 0xFF141A12.toInt(), 0xFF232D1F.toInt(), 0xFFA7C080.toInt()),
        Preset("Zeytin", "Zeytin", "Doğa", 0xFF13140C.toInt(), 0xFF212315.toInt(), 0xFF9CAF4A.toInt()),
        Preset("Bambu", "Bambu", "Doğa", 0xFF0F160C.toInt(), 0xFF1A2615.toInt(), 0xFF8BC34A.toInt()),
        Preset("Nane", "Nane", "Doğa", 0xFF0C1714.toInt(), 0xFF152723.toInt(), 0xFF3EECAC.toInt()),
        Preset("Turkuaz", "Turkuaz", "Doğa", 0xFF061516.toInt(), 0xFF0D2627.toInt(), 0xFF1ABC9C.toInt()),
        Preset("Buzul", "Buzul", "Doğa", 0xFF0D161C.toInt(), 0xFF17252F.toInt(), 0xFFA5D8F3.toInt()),
        Preset("KutupIsigi", "Kutup Işığı", "Doğa", 0xFF0A1416.toInt(), 0xFF12252A.toInt(), 0xFF64FFDA.toInt()),
        Preset("KumFirtinasi", "Kum Fırtınası", "Doğa", 0xFF18150E.toInt(), 0xFF28231A.toInt(), 0xFFD4B483.toInt()),
        Preset("GunBatimi", "Gün Batımı", "Doğa", 0xFF1A0F14.toInt(), 0xFF2A1820.toInt(), 0xFFFF7E5F.toInt()),
        Preset("Volkan", "Volkan", "Doğa", 0xFF160A06.toInt(), 0xFF26120B.toInt(), 0xFFFF5722.toInt()),
        Preset("OkyanusMercani", "Okyanus Mercanı", "Doğa", 0xFF0B1418.toInt(), 0xFF14232A.toInt(), 0xFFFF7F50.toInt()),
        Preset("SiberPunk", "Siber Punk", "Neon", 0xFF0D0A14.toInt(), 0xFF1A1426.toInt(), 0xFFFF2A6D.toInt()),
        Preset("Matrix", "Matrix", "Neon", 0xFF020A04.toInt(), 0xFF071A0C.toInt(), 0xFF00FF41.toInt()),
        Preset("NeonIzgara", "Neon Izgara", "Neon", 0xFF120E1C.toInt(), 0xFF211A33.toInt(), 0xFFB57BFF.toInt()),
        Preset("PembeNeon", "Pembe Neon", "Neon", 0xFF170818.toInt(), 0xFF28102A.toInt(), 0xFFFF4FD8.toInt()),
        Preset("MorOtesi", "Mor Ötesi", "Neon", 0xFF0F0818.toInt(), 0xFF1C0F2E.toInt(), 0xFF7C4DFF.toInt()),
        Preset("YildizTozu", "Yıldız Tozu", "Neon", 0xFF0B0B16.toInt(), 0xFF161628.toInt(), 0xFFE0C3FC.toInt()),
        Preset("TokyoGecesi", "Tokyo Gecesi", "Neon", 0xFF16161E.toInt(), 0xFF1A1B26.toInt(), 0xFF7AA2F7.toInt()),
        Preset("Mocha", "Mocha", "Sıcak", 0xFF1A120C.toInt(), 0xFF2A1E15.toInt(), 0xFFE0B07A.toInt()),
        Preset("Kerpic", "Kerpiç", "Sıcak", 0xFF1A110B.toInt(), 0xFF2C1C12.toInt(), 0xFFF07E3C.toInt()),
        Preset("Bakir", "Bakır", "Sıcak", 0xFF170F0B.toInt(), 0xFF271A13.toInt(), 0xFFC87533.toInt()),
        Preset("Kehribar", "Kehribar", "Sıcak", 0xFF1A1206.toInt(), 0xFF2B1F0C.toInt(), 0xFFFFB300.toInt()),
        Preset("KizilGezegen", "Kızıl Gezegen", "Sıcak", 0xFF1A0B07.toInt(), 0xFF2C150E.toInt(), 0xFFE2582E.toInt()),
        Preset("GulAltin", "Gül Altın", "Sıcak", 0xFF1A1214.toInt(), 0xFF2A1D20.toInt(), 0xFFE8A49C.toInt()),
        Preset("Sarap", "Şarap", "Sıcak", 0xFF16080C.toInt(), 0xFF250F16.toInt(), 0xFFA4243B.toInt()),
        Preset("Kiraz", "Kiraz", "Sıcak", 0xFF19090E.toInt(), 0xFF2A1118.toInt(), 0xFFFF4D6D.toInt()),
        Preset("Sakiz", "Sakız", "Sıcak", 0xFF1A0D14.toInt(), 0xFF2B1622.toInt(), 0xFFF27BAA.toInt()),
        Preset("Yakut", "Yakut", "Taş", 0xFF1A0709.toInt(), 0xFF2B0F13.toInt(), 0xFFE0115F.toInt()),
        Preset("Zumrut", "Zümrüt", "Taş", 0xFF07160F.toInt(), 0xFF0F271C.toInt(), 0xFF2ECC71.toInt()),
        Preset("Safir", "Safir", "Taş", 0xFF0C1426.toInt(), 0xFF18233F.toInt(), 0xFF7FA8F5.toInt()),
        Preset("Ametist", "Ametist", "Taş", 0xFF150E1F.toInt(), 0xFF241835.toInt(), 0xFF9B59B6.toInt()),
        Preset("Lavanta", "Lavanta", "Taş", 0xFF15121C.toInt(), 0xFF231E2E.toInt(), 0xFFB39DDB.toInt()),
        Preset("Dracula", "Dracula", "Kodlama", 0xFF1E1F29.toInt(), 0xFF282A36.toInt(), 0xFFBD93F9.toInt()),
        Preset("Nord", "Nord", "Kodlama", 0xFF1B2029.toInt(), 0xFF2E3440.toInt(), 0xFF88C0D0.toInt()),
        Preset("Gruvbox", "Gruvbox", "Kodlama", 0xFF1D2021.toInt(), 0xFF282828.toInt(), 0xFFFABD2F.toInt()),
        Preset("Catppuccin", "Catppuccin", "Kodlama", 0xFF11111B.toInt(), 0xFF1E1E2E.toInt(), 0xFFCBA6F7.toInt()),
        Preset("Solarized", "Solarized", "Kodlama", 0xFF002029.toInt(), 0xFF073642.toInt(), 0xFF2AA198.toInt()),
        Preset("Monokai", "Monokai", "Kodlama", 0xFF1E1F1C.toInt(), 0xFF272822.toInt(), 0xFFA6E22E.toInt()),
    )

    val groups = listOf("Tümü", "Klasik", "Doğa", "Neon", "Sıcak", "Taş", "Kodlama")

    fun currentKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, "") ?: ""

    fun current(context: Context): Preset? =
        currentKey(context).let { k -> all.firstOrNull { it.key == k } }

    /** Set while MainActivity starts with a preset, cleared on its first
     *  onResume. Still set on the next launch = the last start with this
     *  theme never got that far (crash), so the theme is switched off. */
    private const val BOOT_PENDING_KEY = "dark_theme_boot_pending"

    fun markBootStarted(context: Context) {
        if (currentKey(context).isBlank()) return
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            putBoolean(BOOT_PENDING_KEY, true)
        }
    }

    fun markBootFinished(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(BOOT_PENDING_KEY, false)) {
            prefs.edit { putBoolean(BOOT_PENDING_KEY, false) }
        }
    }

    /** Style resource for the saved preset, or 0 when none is set. */
    fun styleRes(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(BOOT_PENDING_KEY, false)) {
            // Recovery: the previous start with a preset never reached the
            // home screen. Fall back to the default look.
            prefs.edit(commit = true) {
                putBoolean(BOOT_PENDING_KEY, false)
                putString(PREF_KEY, "")
            }
            return 0
        }
        val key = currentKey(context)
        if (key.isBlank() || all.none { it.key == key }) return 0
        return context.resources.getIdentifier(
            "DarkPreset_$key", "style", context.packageName
        )
    }

    private fun save(activity: Activity, key: String) {
        PreferenceManager.getDefaultSharedPreferences(activity).edit {
            putString(PREF_KEY, key)
        }
        activity.recreate()
    }

    /** The "Tema Seç" sheet (approved mockup). */
    fun showPicker(activity: Activity) {
        val ctx = activity
        val selectedKey = currentKey(ctx)
        val sel = all.firstOrNull { it.key == selectedKey }
        val sheetBg = sel?.surface ?: Color.rgb(0x1C, 0x1C, 0x1E)
        val accent = sel?.accent ?: Color.rgb(0xF5, 0xC5, 0x42)
        val dialog = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(sheetBg)
                cornerRadii = floatArrayOf(
                    28.toPx.toFloat(), 28.toPx.toFloat(), 28.toPx.toFloat(), 28.toPx.toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }
        // drag handle
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2.toPx.toFloat()
                setColor(Color.argb(64, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(40.toPx, 4.toPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 10.toPx
            }
        })
        // header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.toPx, 14.toPx, 16.toPx, 4.toPx)
        }
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(ctx).apply {
            setText(R.string.dark_theme_title)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(0xF2, 0xF2, 0xF2))
        })
        titles.addView(TextView(ctx).apply {
            text = ctx.getString(
                R.string.dark_theme_subtitle, all.size,
                sel?.name ?: ctx.getString(R.string.dark_theme_default)
            )
            textSize = 13f
            setTextColor(accent)
        })
        header.addView(titles)
        header.addView(TextView(ctx).apply {
            setText(R.string.dark_theme_reset)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(0xC7, 0xC7, 0xCC))
            setPadding(12.toPx, 10.toPx, 12.toPx, 10.toPx)
            visibility = if (sel == null) View.GONE else View.VISIBLE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                save(activity, "")
            }
        })
        root.addView(header)

        // group chips
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.toPx, 10.toPx, 20.toPx, 12.toPx)
        }
        val chipScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        root.addView(chipScroll)

        val adapter = ThemeAdapter(selectedKey, sheetBg) { preset ->
            dialog.dismiss()
            save(activity, preset.key)
        }
        val recycler = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 3)
            this.adapter = adapter
            clipToPadding = false
            setPadding(10.toPx, 4.toPx, 10.toPx, 28.toPx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
            )
        }
        root.addView(recycler)

        val chipViews = mutableListOf<TextView>()
        fun styleChips(active: String) {
            chipViews.forEach { chip ->
                val on = chip.tag == active
                chip.background = GradientDrawable().apply {
                    cornerRadius = 17.toPx.toFloat()
                    if (on) setColor(accent) else {
                        setColor(Color.TRANSPARENT)
                        setStroke(1.toPx, Color.argb(36, 255, 255, 255))
                    }
                }
                chip.setTextColor(if (on) Color.rgb(0x12, 0x12, 0x12) else Color.rgb(0xE5, 0xE5, 0xEA))
            }
        }
        groups.forEachIndexed { index, group ->
            val chip = TextView(ctx).apply {
                text = group
                tag = group
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(14.toPx, 0, 14.toPx, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 34.toPx
                ).apply { if (index > 0) marginStart = 8.toPx }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    adapter.submit(if (group == groups.first()) all else all.filter { it.group == group })
                    styleChips(group)
                }
            }
            chipViews += chip
            chipRow.addView(chip)
        }
        styleChips(groups.first())
        adapter.submit(all)

        dialog.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private class ThemeAdapter(
        private val selectedKey: String,
        private val sheetBg: Int,
        private val onPick: (Preset) -> Unit,
    ) : RecyclerView.Adapter<ThemeAdapter.Holder>() {
        private var items: List<Preset> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Preset>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(
            val root: LinearLayout,
            val card: FrameLayout,
            val dot: View,
            val inner: LinearLayout,
            val pill: View,
            val square: View,
            val check: ImageView,
            val name: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            fun line(widthWeight: Float, alpha: Int, h: Int) = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3.toPx.toFloat()
                    setColor(Color.argb(alpha, 255, 255, 255))
                }
                layoutParams = LinearLayout.LayoutParams(0, h.toPx, widthWeight)
            }
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(10.toPx, 10.toPx).apply { marginEnd = 6.toPx }
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot)
                addView(line(1f, 140, 5))
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8.toPx, 8.toPx, 8.toPx, 8.toPx)
                addView(LinearLayout(ctx).apply {
                    addView(line(0.8f, 115, 4)); addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 0.2f)
                    })
                })
                addView(LinearLayout(ctx).apply {
                    setPadding(0, 5.toPx, 0, 0)
                    addView(line(0.55f, 64, 4)); addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 0.45f)
                    })
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.toPx }
            }
            val pill = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 12.toPx, 1f)
            }
            val square = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(12.toPx, 12.toPx).apply { marginStart = 6.toPx }
            }
            val bottom = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill)
                addView(square)
            }
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.toPx, 10.toPx, 10.toPx, 10.toPx)
                addView(top)
                addView(inner)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
                })
                addView(bottom)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val check = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_check_24)
                setPadding(3.toPx, 3.toPx, 3.toPx, 3.toPx)
                layoutParams = FrameLayout.LayoutParams(22.toPx, 22.toPx, Gravity.TOP or Gravity.END)
                    .apply { topMargin = 4.toPx; marginEnd = 4.toPx }
            }
            val card = FrameLayout(ctx).apply {
                addView(column)
                addView(check)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 128.toPx
                )
            }
            val name = TextView(ctx).apply {
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 7.toPx }
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6.toPx, 6.toPx, 6.toPx, 8.toPx)
                isClickable = true
                isFocusable = true
                addView(card)
                addView(name)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return Holder(root, card, dot, inner, pill, square, check, name)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = items[position]
            val selected = p.key == selectedKey
            holder.card.background = GradientDrawable().apply {
                cornerRadius = 16.toPx.toFloat()
                setColor(p.bg)
                if (selected) setStroke(2.toPx, p.accent)
                else setStroke(1.toPx, Color.argb(26, 255, 255, 255))
            }
            holder.dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.accent)
            }
            holder.inner.background = GradientDrawable().apply {
                cornerRadius = 9.toPx.toFloat()
                setColor(p.surface)
            }
            holder.pill.background = GradientDrawable().apply {
                cornerRadius = 6.toPx.toFloat()
                setColor(p.accent)
            }
            holder.square.background = GradientDrawable().apply {
                cornerRadius = 3.toPx.toFloat()
                setColor(p.surface)
            }
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE
            holder.check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.accent)
                setStroke(2.toPx, sheetBg)
            }
            holder.check.imageTintList = ColorStateList.valueOf(Color.rgb(0x12, 0x12, 0x12))
            holder.name.text = p.name
            holder.name.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.name.setTextColor(if (selected) p.accent else Color.rgb(0xC7, 0xC7, 0xCC))
            holder.root.contentDescription = p.name
            holder.root.setOnClickListener { onPick(p) }
        }
    }
}
