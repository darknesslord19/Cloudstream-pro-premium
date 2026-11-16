package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.plugins.DomainOverrides
import com.lagradost.cloudstream3.plugins.DomainOverrides.CheckResult
import com.lagradost.cloudstream3.plugins.DomainOverrides.Source
import com.lagradost.cloudstream3.plugins.DomainOverrides.Status
import com.lagradost.cloudstream3.utils.SheetChrome
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CloudStream Dark: Settings › Plugin addresses.
 * Lists every CloudStream plugin's web address; tap one to change only the
 * extension or the whole address, or let the app find where it moved.
 */
class DomainOverridesFragment : Fragment() {

    private var content: LinearLayout? = null
    private var filterChanged = false
    private var scanning = false
    private var scanned = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getResourceColor(R.attr.primaryBlackBackground))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.toPx, 8.toPx, 8.toPx, 8.toPx)
            setBackgroundColor(ctx.getResourceColor(R.attr.primaryGrayBackground))
        }
        bar.addView(iconButton(ctx, R.drawable.ic_baseline_arrow_back_24, R.string.sky_back) {
            findNavController().navigateUp()
        })
        bar.addView(TextView(ctx).apply {
            setText(R.string.domain_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ctx.getResourceColor(R.attr.textColor))
            setPadding(6.toPx, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val scroll = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.toPx, 14.toPx, 16.toPx, 140.toPx)
        }
        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content = inner

        with(com.lagradost.cloudstream3.ui.FloatingNavInsets) {
            inner.followFloatingNav { h -> setBottomPaddingPx(h + 24.toPx) }
        }
        val baseTop = bar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestApplyInsets()
        render()
    }

    // ------------------------------------------------------------------ UI

    private fun color(attr: Int) = requireContext().getResourceColor(attr)
    private val accent get() = color(androidx.appcompat.R.attr.colorPrimary)

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp.toPx.toFloat()
        if (stroke != null) setStroke(1.toPx, stroke)
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    private fun iconButton(
        ctx: Context, icon: Int, desc: Int, sizeDp: Int = 44, bg: android.graphics.drawable.Drawable? = null,
        tint: Int? = null, onClick: () -> Unit,
    ) = ImageButton(ctx).apply {
        setImageResource(icon)
        contentDescription = ctx.getString(desc)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, ctx.getString(desc))
        background = bg ?: rippleBg(ctx)
        imageTintList = ColorStateList.valueOf(tint ?: ctx.getResourceColor(R.attr.iconColor))
        layoutParams = LinearLayout.LayoutParams(sizeDp.toPx, sizeDp.toPx)
        setOnClickListener { onClick() }
    }

    private fun rippleBg(ctx: Context): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
    }

    private fun text(ctx: Context, s: CharSequence, sp: Float, colorAttr: Int, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(ctx.getResourceColor(colorAttr))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun mono(tv: TextView) = tv.apply { typeface = Typeface.MONOSPACE }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx.getResourceColor(R.attr.boxItemBackground), 18)
        clipToOutline = true
    }

    private fun gap(v: View, topDp: Int) {
        (v.layoutParams as? ViewGroup.MarginLayoutParams ?: ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { v.layoutParams = it }).topMargin = topDp.toPx
    }

    private fun srcShort(s: Source) = getString(
        when (s) {
            Source.REDIRECT -> R.string.domain_src_redirect
            Source.NUMBER -> R.string.domain_src_number
            Source.TLD -> R.string.domain_src_tld
            Source.ADMIN -> R.string.domain_src_admin
        }
    )

    private fun srcLong(s: Source) = getString(
        when (s) {
            Source.REDIRECT -> R.string.domain_src_redirect_long
            Source.NUMBER -> R.string.domain_src_number_long
            Source.TLD -> R.string.domain_src_tld_long
            Source.ADMIN -> R.string.domain_src_admin_long
        }
    )

    private fun isChanged(api: MainAPI) = DomainOverrides.userUrlFor(api) != null

    private fun pendingFound(api: MainAPI): DomainOverrides.Candidate? {
        val r = results[DomainOverrides.keyOf(api)] ?: return null
        if (r.status != Status.DOWN) return null
        return r.candidates.firstOrNull { it.url != api.mainUrl.trimEnd('/') }
    }

    fun render() {
        val ctx = context ?: return
        val box = content ?: return
        box.removeAllViews()
        val apis = DomainOverrides.editableApis()
        val changed = apis.count { isChanged(it) }

        // Filters
        val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            getString(R.string.domain_filter_all, apis.size) to false,
            getString(R.string.domain_filter_changed, changed) to true,
        ).forEach { (label, isChangedFilter) ->
            val on = filterChanged == isChangedFilter
            filters.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(16.toPx, 0, 16.toPx, 0)
                setTextColor(if (on) color(com.google.android.material.R.attr.colorOnPrimary) else color(R.attr.textColor))
                background = if (on) rounded(accent, 18) else rounded(0, 18, withAlpha(color(R.attr.textColor), .18f))
                setOnClickListener { filterChanged = isChangedFilter; render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.toPx).apply { marginEnd = 8.toPx })
        }
        box.addView(filters)

        // Summary card
        val found = apis.mapNotNull { a -> pendingFound(a)?.let { a to it } }
        val summary = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.attr.boxItemBackground), 18)
            setPadding(16.toPx, 14.toPx, 14.toPx, 14.toPx)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(ctx, when {
            scanning -> getString(R.string.domain_scanning)
            scanned && found.isNotEmpty() -> getString(R.string.domain_found_title, found.size)
            scanned -> getString(R.string.domain_scan_done)
            else -> getString(R.string.domain_summary_title, apis.size)
        }, 16f, R.attr.textColor, true))
        texts.addView(text(ctx,
            if (scanning) getString(R.string.domain_scanning_sub) else getString(R.string.domain_summary_sub, changed),
            12.5f, R.attr.grayTextColor))
        summary.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (found.isNotEmpty() && !scanning) {
            summary.addView(iconButton(ctx, R.drawable.ic_domain_apply_all, R.string.domain_apply_all,
                bg = rounded(accent, 14), tint = color(com.google.android.material.R.attr.colorOnPrimary)) {
                found.forEach { (a, c) -> DomainOverrides.setUserUrl(a, c.url) }
                render()
            }.also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = 8.toPx })
        }
        if (scanning) {
            summary.addView(ProgressBar(ctx).apply {
                indeterminateTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(44.toPx, 44.toPx).apply { marginStart = 8.toPx })
        } else {
            summary.addView(iconButton(ctx, R.drawable.ic_domain_scan, R.string.domain_scan,
                bg = rounded(withAlpha(accent, .16f), 14), tint = accent) { scanAll() }
                .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = 8.toPx })
        }
        summary.addView(iconButton(ctx, R.drawable.ic_domain_reset, R.string.domain_reset_all,
            bg = rounded(0, 14, withAlpha(color(R.attr.textColor), .14f))) {
            DomainOverrides.clearAllUser(); render()
        }.also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = 8.toPx })
        box.addView(summary); gap(summary, 14)

        // Auto check toggle
        val auto = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.attr.boxItemBackground), 18)
            setPadding(16.toPx, 10.toPx, 12.toPx, 10.toPx)
        }
        val autoTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        autoTexts.addView(text(ctx, getString(R.string.domain_auto_title), 14.5f, R.attr.textColor, true))
        autoTexts.addView(text(ctx, getString(R.string.domain_auto_sub), 12f, R.attr.grayTextColor))
        auto.addView(autoTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(ctx).apply { isChecked = DomainOverrides.autoCheck }
        sw.setOnCheckedChangeListener { _, v -> DomainOverrides.autoCheck = v }
        auto.addView(sw)
        auto.setOnClickListener { sw.toggle() }
        box.addView(auto); gap(auto, 12)

        // List
        val shown = if (filterChanged) apis.filter { isChanged(it) } else apis
        if (shown.isEmpty()) {
            val e = text(ctx, getString(R.string.domain_empty), 14f, R.attr.grayTextColor)
            e.gravity = Gravity.CENTER
            e.setPadding(0, 40.toPx, 0, 0)
            box.addView(e)
            return
        }
        val list = card(ctx)
        shown.forEachIndexed { i, api -> list.addView(row(ctx, api, i < shown.lastIndex)) }
        box.addView(list); gap(list, 12)
    }

    private fun row(ctx: Context, api: MainAPI, divider: Boolean): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.toPx, 12.toPx, 10.toPx, 12.toPx)
            background = rippleBgRect(ctx)
            isFocusable = true
            setOnClickListener { openSheet(api) }
        }
        row.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_domain_globe)
            imageTintList = ColorStateList.valueOf(color(R.attr.iconColor))
        }, LinearLayout.LayoutParams(24.toPx, 24.toPx).apply { marginEnd = 14.toPx })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(text(ctx, api.name, 15.5f, R.attr.textColor, true))
        val changed = isChanged(api)
        if (changed) nameRow.addView(TextView(ctx).apply {
            setText(R.string.domain_changed_badge)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
            background = rounded(accent, 6)
            setPadding(6.toPx, 2.toPx, 6.toPx, 2.toPx)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.toPx })
        col.addView(nameRow)

        val (base, tld) = DomainOverrides.splitTld(api.mainUrl)
        val url = android.text.SpannableStringBuilder(base.substringAfter("://")).apply {
            val st = length
            append(tld)
            setSpan(android.text.style.ForegroundColorSpan(accent), st, length, 0)
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
        }
        col.addView(mono(text(ctx, url, 12.5f, R.attr.grayTextColor)).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (changed) col.addView(mono(text(ctx, DomainOverrides.originalOf(api), 11f, R.attr.grayTextColor)).apply {
            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            alpha = .6f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })

        results[DomainOverrides.keyOf(api)]?.let { r ->
            val pending = pendingFound(api)
            val (label, c) = when {
                r.status == Status.OK -> getString(R.string.domain_status_ok) to 0xFF7FD49A.toInt()
                r.status == Status.PROTECTED -> getString(R.string.domain_status_protected) to color(R.attr.grayTextColor)
                pending == null && r.candidates.any { it.url == api.mainUrl.trimEnd('/') } ->
                    getString(R.string.domain_status_applied) to 0xFF7FD49A.toInt()
                pending != null -> getString(R.string.domain_status_down) to 0xFFF08A6C.toInt()
                else -> getString(R.string.domain_status_dead) to 0xFFF08A6C.toInt()
            }
            col.addView(TextView(ctx).apply {
                text = "●  $label"; textSize = 11.5f; setTextColor(c)
            })
            if (pending != null) col.addView(foundStrip(ctx, api, pending))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_domain_edit)
            imageTintList = ColorStateList.valueOf(color(R.attr.iconColor))
            setPadding(10.toPx, 10.toPx, 10.toPx, 10.toPx)
        }, LinearLayout.LayoutParams(40.toPx, 40.toPx))
        wrap.addView(row)
        if (divider) wrap.addView(View(ctx).apply {
            setBackgroundColor(withAlpha(color(R.attr.textColor), .06f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return wrap
    }

    private fun rippleBgRect(ctx: Context): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
    }

    private fun foundStrip(ctx: Context, api: MainAPI, c: DomainOverrides.Candidate): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(withAlpha(accent, .10f), 10, withAlpha(accent, .35f))
            setPadding(10.toPx, 6.toPx, 6.toPx, 6.toPx)
        }
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(ctx).apply {
            text = getString(R.string.domain_new_address, srcShort(c.source))
            textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent)
        })
        t.addView(mono(text(ctx, c.url, 12f, R.attr.textColor)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        strip.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        strip.addView(iconButton(ctx, R.drawable.ic_domain_check, R.string.domain_save, sizeDp = 34,
            bg = rounded(accent, 10), tint = color(com.google.android.material.R.attr.colorOnPrimary)) {
            DomainOverrides.setUserUrl(api, c.url); render()
        })
        strip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 6.toPx }
        return strip
    }

    // ------------------------------------------------------------ scanning

    private val results get() = DomainOverrides.lastResults

    private fun scanAll() {
        if (scanning) return
        scanning = true
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { DomainOverrides.checkAll() }
            scanning = false
            scanned = true
            render()
        }
    }

    // --------------------------------------------------------------- sheet

    private fun openSheet(api: MainAPI) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.toPx, 8.toPx, 18.toPx, 24.toPx)
            background = rounded(color(R.attr.boxItemBackground), 0).apply {
                cornerRadii = floatArrayOf(26f.dpF, 26f.dpF, 26f.dpF, 26f.dpF, 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(SheetChrome.dragHandle(ctx))

        val original = DomainOverrides.originalOf(api)
        val current = api.mainUrl.trimEnd('/')
        var fullMode = false
        var tested: Long? = null
        var testFailed = false

        // Header
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val headTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        headTexts.addView(text(ctx, api.name, 18f, R.attr.textColor, true))
        headTexts.addView(text(ctx, getString(R.string.domain_plugin_address, original), 12f, R.attr.grayTextColor))
        head.addView(headTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconButton(ctx, R.drawable.ic_baseline_close_24, R.string.cancel, sizeDp = 40) { dialog.dismiss() })
        sheet.addView(head)

        // Segmented
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(R.attr.primaryBlackBackground), 14)
            setPadding(4.toPx, 4.toPx, 4.toPx, 4.toPx)
        }
        val tabTld = TextView(ctx); val tabFull = TextView(ctx)
        listOf(tabTld to R.string.domain_mode_tld, tabFull to R.string.domain_mode_full).forEach { (tv, s) ->
            tv.setText(s); tv.gravity = Gravity.CENTER; tv.textSize = 14.5f; tv.typeface = Typeface.DEFAULT_BOLD
            seg.addView(tv, LinearLayout.LayoutParams(0, 40.toPx, 1f))
        }
        sheet.addView(seg); gap(seg, 16)

        // TLD mode
        val (base, tld) = DomainOverrides.splitTld(current)
        val tldBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tldRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.attr.primaryBlackBackground), 14)
            setPadding(14.toPx, 0, 6.toPx, 0)
        }
        tldRow.addView(mono(text(ctx, base, 14.5f, R.attr.grayTextColor)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tldInput = EditText(ctx).apply {
            setText(tld)
            typeface = Typeface.MONOSPACE; textSize = 15f
            setTextColor(accent)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = rounded(withAlpha(accent, .10f), 10, withAlpha(accent, .45f))
            setPadding(10.toPx, 0, 10.toPx, 0)
            minWidth = 90.toPx
        }
        tldRow.addView(tldInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40.toPx).apply { marginStart = 2.toPx })
        tldBox.addView(tldRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.toPx))
        val chipsScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chips)
        tldBox.addView(chipsScroll); gap(chipsScroll, 10)
        sheet.addView(tldBox); gap(tldBox, 14)

        // Full mode
        val fullBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fullInput = EditText(ctx).apply {
            setText(current)
            typeface = Typeface.MONOSPACE; textSize = 14.5f
            setTextColor(color(R.attr.textColor))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = rounded(color(R.attr.primaryBlackBackground), 14, withAlpha(color(R.attr.textColor), .14f))
            setPadding(14.toPx, 0, 14.toPx, 0)
        }
        fullBox.addView(fullInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.toPx))
        fullBox.addView(text(ctx, getString(R.string.domain_full_hint), 12f, R.attr.grayTextColor).also { gap(it, 8) })
        sheet.addView(fullBox); gap(fullBox, 14)

        // Candidates
        val candBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val candHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        candHead.addView(text(ctx, getString(R.string.domain_candidates), 11f, R.attr.grayTextColor, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val candList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val candProgress = ProgressBar(ctx).apply { isVisible = false; indeterminateTintList = ColorStateList.valueOf(accent) }
        val searchBtn = TextView(ctx).apply {
            text = getString(R.string.domain_search)
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.attr.textColor))
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_domain_search, 0, 0, 0)
            compoundDrawablePadding = 6.toPx
            compoundDrawableTintList = ColorStateList.valueOf(color(R.attr.iconColor))
            background = rounded(0, 10, withAlpha(color(R.attr.textColor), .14f))
            setPadding(10.toPx, 0, 12.toPx, 0)
        }
        candHead.addView(candProgress, LinearLayout.LayoutParams(24.toPx, 24.toPx).apply { marginEnd = 8.toPx })
        candHead.addView(searchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 32.toPx))
        candBox.addView(candHead)
        candBox.addView(candList); gap(candList, 8)
        sheet.addView(candBox); gap(candBox, 16)

        // Preview
        val preview = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.attr.primaryBlackBackground), 14)
            setPadding(14.toPx, 12.toPx, 12.toPx, 12.toPx)
        }
        val pvTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pvTexts.addView(text(ctx, getString(R.string.domain_preview), 11f, R.attr.grayTextColor, true))
        val pvUrl = mono(text(ctx, "", 13.5f, R.attr.textColor))
        pvTexts.addView(pvUrl)
        preview.addView(pvTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(ctx).apply { textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(8.toPx, 5.toPx, 8.toPx, 5.toPx) }
        preview.addView(badge)
        sheet.addView(preview); gap(preview, 16)

        fun newUrl(): String {
            return if (fullMode) fullInput.text.toString().trim().trimEnd('/')
            else {
                var t = tldInput.text.toString().trim()
                if (t.isNotEmpty() && !t.startsWith(".")) t = ".$t"
                base + t
            }
        }

        fun refresh() {
            val on = color(R.attr.textColor)
            tabTld.setTextColor(if (!fullMode) on else color(R.attr.grayTextColor))
            tabFull.setTextColor(if (fullMode) on else color(R.attr.grayTextColor))
            tabTld.background = if (!fullMode) rounded(color(R.attr.boxItemBackground), 11) else null
            tabFull.background = if (fullMode) rounded(color(R.attr.boxItemBackground), 11) else null
            tldBox.isVisible = !fullMode
            fullBox.isVisible = fullMode
            pvUrl.text = newUrl()
            val t = tested
            when {
                testFailed -> { badge.setText(R.string.domain_unreached); badge.setTextColor(0xFFF08A6C.toInt()); badge.background = rounded(withAlpha(0xFFF08A6C.toInt(), .12f), 8) }
                t != null -> { badge.text = getString(R.string.domain_reached, t.toInt()); badge.setTextColor(0xFF7FD49A.toInt()); badge.background = rounded(withAlpha(0xFF7FD49A.toInt(), .12f), 8) }
                else -> { badge.setText(R.string.domain_untested); badge.setTextColor(color(R.attr.grayTextColor)); badge.background = rounded(withAlpha(color(R.attr.textColor), .06f), 8) }
            }
            // TLD chips
            chips.removeAllViews()
            val typed = newUrl().substring(base.length).lowercase()
            listOf(".com", ".net", ".org", ".tv", ".to", ".co", ".me", ".cc", ".live", ".xyz", ".site", ".pro", ".nl", ".de", ".ws")
                .forEach { s ->
                    val sel = typed == s
                    chips.addView(TextView(ctx).apply {
                        text = s; typeface = Typeface.MONOSPACE; textSize = 13f; gravity = Gravity.CENTER
                        setTextColor(if (sel) accent else color(R.attr.textColor))
                        background = if (sel) rounded(withAlpha(accent, .16f), 10, accent)
                        else rounded(color(R.attr.primaryBlackBackground), 10, withAlpha(color(R.attr.textColor), .08f))
                        setPadding(12.toPx, 0, 12.toPx, 0)
                        setOnClickListener {
                            tldInput.setText(s); tested = null; testFailed = false; refresh()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.toPx).apply { marginEnd = 8.toPx })
                }
        }

        fun showCandidates(r: CheckResult?) {
            candList.removeAllViews()
            if (r == null) {
                candList.addView(text(ctx, getString(R.string.domain_candidates_hint), 12f, R.attr.grayTextColor))
                return
            }
            val list = r.candidates
            if (list.isEmpty()) {
                candList.addView(text(ctx, getString(R.string.domain_no_candidates), 12f, R.attr.grayTextColor))
                return
            }
            list.forEach { c ->
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    val sel = newUrl() == c.url
                    background = if (sel) rounded(withAlpha(accent, .12f), 12, accent)
                    else rounded(color(R.attr.primaryBlackBackground), 12, withAlpha(color(R.attr.textColor), .08f))
                    setPadding(12.toPx, 9.toPx, 12.toPx, 9.toPx)
                    setOnClickListener {
                        fullMode = true; fullInput.setText(c.url)
                        tested = c.ms.takeIf { it > 0 }; testFailed = false
                        refresh(); showCandidates(r)
                    }
                }
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(mono(text(ctx, c.url, 12.5f, R.attr.textColor)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                col.addView(text(ctx, srcLong(c.source), 11f, R.attr.grayTextColor))
                item.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (c.ms > 0) item.addView(TextView(ctx).apply {
                    text = "${c.ms} ms"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF7FD49A.toInt())
                })
                candList.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.toPx })
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tested = null; testFailed = false
                pvUrl.text = newUrl()
                badge.setText(R.string.domain_untested); badge.setTextColor(color(R.attr.grayTextColor))
                badge.background = rounded(withAlpha(color(R.attr.textColor), .06f), 8)
            }
        }
        tldInput.addTextChangedListener(watcher)
        fullInput.addTextChangedListener(watcher)
        tabTld.setOnClickListener { fullMode = false; refresh() }
        tabFull.setOnClickListener {
            fullInput.setText(newUrl()); fullMode = true; refresh()
        }
        searchBtn.setOnClickListener {
            candProgress.isVisible = true; searchBtn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { DomainOverrides.checkOne(api) }
                candProgress.isVisible = false; searchBtn.isEnabled = true
                showCandidates(r); render()
            }
        }

        // Actions
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val outline = { rounded(0, 16, withAlpha(color(R.attr.textColor), .14f)) }
        actions.addView(iconButton(ctx, R.drawable.ic_domain_reset, R.string.domain_reset_one, sizeDp = 52, bg = outline()) {
            DomainOverrides.setUserUrl(api, null); dialog.dismiss(); render()
        })
        actions.addView(iconButton(ctx, R.drawable.ic_domain_test, R.string.domain_test, sizeDp = 52, bg = outline()) {
            val u = newUrl()
            viewLifecycleOwner.lifecycleScope.launch {
                val ms = withContext(Dispatchers.IO) { DomainOverrides.ping(u) }
                tested = ms; testFailed = ms == null; refresh()
            }
        }.also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = 10.toPx })
        actions.addView(TextView(ctx).apply {
            setText(R.string.domain_save)
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
            background = rounded(accent, 16)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_domain_check, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnPrimary))
            compoundDrawablePadding = 8.toPx
            setPadding(18.toPx, 0, 18.toPx, 0)
            setOnClickListener {
                val u = newUrl()
                if (!u.matches(Regex("""^https?://[^\s/]+\.[^\s/]+.*$"""))) {
                    showToast(R.string.domain_invalid); return@setOnClickListener
                }
                DomainOverrides.setUserUrl(api, u)
                showToast(R.string.domain_saved)
                dialog.dismiss(); render()
            }
        }, LinearLayout.LayoutParams(0, 52.toPx, 1f).apply { marginStart = 10.toPx })
        sheet.addView(actions); gap(actions, 16)

        refresh()
        showCandidates(results[DomainOverrides.keyOf(api)])

        val scroll = ScrollView(ctx).apply { addView(sheet) }
        dialog.setContentView(scroll)
        (scroll.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    private val Float.dpF get() = this * resources.displayMetrics.density
}
