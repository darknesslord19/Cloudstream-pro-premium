package com.lagradost.cloudstream3.htmlapi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R

/** HTML arayüz adresini kopyalama düğmeleriyle gösteren pencere ve ilgili ayarlar. */
object HtmlApiDialog {
    const val PREF_ENABLED = "html_api_enabled"
    const val PREF_POPUP = "html_api_popup"

    fun isEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, true)

    fun popupEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_POPUP, true)

    /** Ayarlar satırında görünen kısa özet. */
    fun summary(ctx: Context): String = when {
        !isEnabled(ctx) -> "Kapalı"
        !HtmlApiServer.isRunning() -> "Başlatılıyor…"
        else -> HtmlApiServer.addresses().joinToString("\n").ifEmpty { "Wi-Fi bağlantısı yok" }
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("CloudStream HTML", text))
        Toast.makeText(ctx, "Kopyalandı: $text", Toast.LENGTH_SHORT).show()
    }

    /**
     * @param fromStartup açılışta gösteriliyorsa "Bir daha gösterme" düğmesi eklenir.
     */
    fun show(ctx: Context, fromStartup: Boolean = false) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(4))
        }
        val info = TextView(ctx).apply {
            text = if (isEnabled(ctx))
                "Aynı Wi-Fi ağındaki bir cihazın tarayıcısında bu adresi aç ya da CloudStream.html dosyasına gir:"
            else
                "HTML arayüz sunucusu kapalı. Ayarlar → Genel → HTML arayüz bölümünden açabilirsin."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(info)

        val addrs = if (isEnabled(ctx)) HtmlApiServer.addresses() else emptyList()
        if (isEnabled(ctx) && addrs.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "Wi-Fi / yerel ağ bağlantısı bulunamadı."
                setPadding(0, px(12), 0, 0)
            })
        }
        addrs.forEach { addr ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, 0)
            }
            val tv = TextView(ctx).apply {
                text = addr
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val btn = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_sheet_copy)
                setColorFilter(tv.currentTextColor, PorterDuff.Mode.SRC_IN)
                contentDescription = "Kopyala"
                val outValue = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setPadding(px(10), px(10), px(10), px(10))
                isFocusable = true
                setOnClickListener { copy(ctx, addr) }
            }
            row.addView(btn, LinearLayout.LayoutParams(px(48), px(48)))
            tv.setOnClickListener { copy(ctx, addr) }
            root.addView(row)
        }

        val b = AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle("HTML arayüz")
            .setView(root)
            .setPositiveButton("Tamam", null)
        if (fromStartup) {
            b.setNeutralButton("Bir daha gösterme") { _, _ ->
                PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(PREF_POPUP, false).apply()
                Toast.makeText(ctx, "Adrese Ayarlar → Genel → HTML arayüz bölümünden ulaşabilirsin", Toast.LENGTH_LONG).show()
            }
        }
        val dialog = b.show()
        // Kumandada odak doğrudan ilk kopyala düğmesine gelsin
        root.post { root.findFocus() ?: (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<LinearLayout>().firstOrNull()?.getChildAt(1)?.requestFocus() }
        dialog.setCanceledOnTouchOutside(true)
    }
}
