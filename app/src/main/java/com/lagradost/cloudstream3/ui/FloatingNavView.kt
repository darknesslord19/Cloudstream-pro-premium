package com.lagradost.cloudstream3.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.lagradost.cloudstream3.R
import kotlin.math.abs

/**
 * Phase 8.99d — the floating bottom bar (PHONE layout only; TV keeps the
 * stock BottomNavigationView, untouched).
 *
 * Presentation: a dark pill floating above the content. The selected
 * destination expands — a tonal-grey organic blob grows behind its icon
 * and its label slides in — while the other destinations stay icon-only.
 * Highlight colour is the app's tonal grey family (@color/extButtonBackground);
 * red stays Play-only.
 *
 * State ownership: this view is presentation + touch only. The (now
 * INVISIBLE) BottomNavigationView remains the selection/state holder
 * wired to the NavController. Taps and scrubs are forwarded through
 * [onNavigateRequest]; MainActivity pushes them into the
 * BottomNavigationView and reads the true result back via
 * [syncSelection], so the existing debounced navigate listener and the
 * checked-state bookkeeping in updateNavBar run exactly as before.
 *
 * Phase 8.99e: the highlight is no longer a separate View chasing the
 * selected item from onLayout (the stretched vector ellipse lagged and
 * sat off-centre on device). The selected item now carries the blob as
 * its OWN background — an organic-radius GradientDrawable drawn by the
 * item itself, so it tracks the animated width exactly and the
 * icon+label group is centred inside it by construction.
 */
class FloatingNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private data class NavItem(
        val id: Int,
        val iconRes: Int,
        val labelRes: Int,
    )

    /** Same six destinations, same order as menu/bottom_nav_menu.xml. */
    private val navItems = listOf(
        NavItem(R.id.navigation_home, R.drawable.home_icon_outline_24, R.string.title_home),
        NavItem(R.id.navigation_search, R.drawable.search_icon, R.string.title_search),
        NavItem(
            R.id.navigation_feed,
            R.drawable.ic_baseline_ondemand_video_24,
            R.string.title_feed
        ),
        NavItem(R.id.navigation_library, R.drawable.library_icon, R.string.library),
        NavItem(R.id.navigation_downloads, R.drawable.netflix_download, R.string.title_downloads),
        NavItem(
            R.id.navigation_settings,
            R.drawable.settings_icon_outline,
            R.string.title_settings
        ),
    )

    // The two sub-destination groups MainActivity.updateNavBar maps onto a
    // tab (its `when` uses the same lists on destination.id).
    private val downloadGroup = listOf(
        R.id.navigation_downloads,
        R.id.navigation_download_child,
        R.id.navigation_download_queue,
    )
    private val settingsGroup = listOf(
        R.id.navigation_settings,
        R.id.navigation_subtitles,
        R.id.navigation_chrome_subtitles,
        R.id.navigation_settings_player,
        R.id.navigation_settings_updates,
        R.id.navigation_settings_ui,
        R.id.navigation_settings_account,
        R.id.navigation_settings_providers,
        R.id.navigation_settings_general,
        R.id.navigation_settings_extensions,
        R.id.navigation_settings_plugins,
        R.id.navigation_test_providers,
    )

    /** A tap/scrub wants this destination. MainActivity routes it through
     *  the BottomNavigationView and calls [syncSelection] with the result. */
    var onNavigateRequest: ((Int) -> Unit)? = null

    /** The already-selected item was tapped again (Feed scroll-to-top). */
    var onReselected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()

    private val itemHeightPx = dp(50f)
    private val collapsedWidthPx = dp(50f)
    private val iconPx = dp(24f)
    private val itemPadPx = dp(13f)
    private val labelGapPx = dp(8f)
    // CloudStream Dark: sits closer to the edge (was 14dp); the system
    // navigation inset is still added on top, so it never covers the
    // phone's own back/home buttons.
    private val baseBottomLiftPx = dp(4f)
    private val scrubThresholdPx = dp(8f).toFloat()

    private val inactiveTint = Color.rgb(150, 150, 150)
    /** Theme colours: the accent (colorPrimary) marks the selected tab and
     *  the bar itself uses the theme's card colour, so the bar follows
     *  whichever theme is active. */
    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
            else tv.data
        } else fallback
    }
    private val activeTint = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.rgb(0xF5, 0xC5, 0x42))
    private val barColor = themeColor(R.attr.primaryGrayBackground, Color.rgb(0x14, 0x14, 0x14))

    /** CloudStream Dark: icon-only bar unless the user turned labels on
     *  (Settings → Layout → "Bottom bar labels"). */
    private var showLabels = readShowLabels()

    private fun readShowLabels(): Boolean = runCatching {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(NAV_LABELS_KEY, false)
    }.getOrDefault(false)

    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NAV_LABELS_KEY) {
                val value = readShowLabels()
                if (value != showLabels) {
                    showLabels = value
                    applySelection(animate = false)
                }
            }
        }

    private val pill: FrameLayout
    private val row: LinearLayout
    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private val expandedWidths = mutableListOf<Int>()

    private var selectedIndex = 0
    private var widthAnimator: ValueAnimator? = null
    private var destinationListener: NavController.OnDestinationChangedListener? = null

    // Drag-to-scrub state (mirrors MainActivity.setupNavScrub semantics).
    private var scrubDownX = 0f
    private var scrubDownY = 0f
    private var scrubbing = false
    private var scrubChangedSelection = false

    val selectedItemId: Int
        get() = navItems[selectedIndex].id

    init {
        // Room around the pill for its elevation shadow; the bottom
        // padding is the bar's lift above the screen edge (plus the
        // system-bar inset on API 30+, mirroring how the flat bar's
        // height grew by the inset — see the inset audit in
        // PHASE899D_CHANGES.md).
        setPadding(dp(8f), dp(6f), dp(8f), baseBottomLiftPx)
        clipChildren = false
        clipToPadding = false

        pill = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(40f).toFloat()
                setColor(barColor)
                setStroke(dp(1f), Color.argb(31, 255, 255, 255))
            }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            elevation = 0f
        }

        row = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pill.addView(row)

        val labelPaint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
            )
        }

        navItems.forEachIndexed { index, item ->
            val labelText = context.getString(item.labelRes)
            val itemView = LinearLayout(context).apply {
                id = item.id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(itemPadPx, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(collapsedWidthPx, itemHeightPx)
                contentDescription = labelText
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageResource(item.iconRes)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = labelGapPx }
                text = labelText
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                alpha = 0f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            itemView.addView(icon)
            itemView.addView(label)
            itemView.setOnClickListener {
                if (item.id == selectedItemId) {
                    onReselected?.invoke(item.id)
                } else {
                    onNavigateRequest?.invoke(item.id)
                }
            }
            val touch = OnTouchListener { _, event -> handleScrubTouch(event) }
            itemView.setOnTouchListener(touch)
            icon.setOnTouchListener(touch)
            label.setOnTouchListener(touch)
            row.addView(itemView)
            itemViews.add(itemView)
            iconViews.add(icon)
            labelViews.add(label)
            expandedWidths.add(
                itemPadPx + iconPx + labelGapPx +
                    labelPaint.measureText(labelText).toInt() + itemPadPx
            )
        }
        addView(pill)

        applySelection(animate = false)

        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBottom =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                view.updatePadding(bottom = baseBottomLiftPx + systemBottom)
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(prefListener)
        }
        val value = readShowLabels()
        if (value != showLabels) {
            showLabels = value
            applySelection(animate = false)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        publishHeight()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        publishHeight()
    }

    private fun publishHeight() {
        FloatingNavInsets.update(if (isShown && height > 0) height else 0)
    }

    override fun onDetachedFromWindow() {
        FloatingNavInsets.update(0)
        widthAnimator?.cancel()
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        super.onDetachedFromWindow()
    }

    /** Read-back sync from the BottomNavigationView (the state owner). */
    fun syncSelection(itemId: Int, animate: Boolean) {
        val index = navItems.indexOfFirst { it.id == itemId }
        if (index < 0) return
        if (index == selectedIndex) {
            if (!animate) applySelection(animate = false)
            return
        }
        selectedIndex = index
        applySelection(animate)
    }

    /** Follow the NavController so back-stack pops and deep links move
     *  the highlight too (the BottomNavigationView's listener is only
     *  driven by taps). */
    fun setupWithNavController(navController: NavController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            tabForDestination(destination)?.let { syncSelection(it, animate = true) }
        }
        destinationListener = listener
        navController.addOnDestinationChangedListener(listener)
    }

    fun detachFromNavController(navController: NavController) {
        destinationListener?.let { navController.removeOnDestinationChangedListener(it) }
        destinationListener = null
    }

    private fun tabForDestination(destination: NavDestination): Int? {
        val hierarchyIds = destination.hierarchy.map { it.id }.toList()
        navItems.firstOrNull { it.id in hierarchyIds }?.let { return it.id }
        if (hierarchyIds.any { it in downloadGroup }) return R.id.navigation_downloads
        if (hierarchyIds.any { it in settingsGroup }) return R.id.navigation_settings
        return null
    }

    /** Library tab icon swap (sync provider icon), mirroring the
     *  BottomNavigationView menu update in MainActivity. */
    fun setItemIcon(itemId: Int, @DrawableRes iconRes: Int) {
        val index = navItems.indexOfFirst { it.id == itemId }
        if (index < 0) return
        iconViews[index].setImageResource(iconRes)
        iconViews[index].setColorFilter(
            if (index == selectedIndex) activeTint else inactiveTint
        )
    }

    /** The item root view (long-press wiring lives in MainActivity). */
    fun getItemView(itemId: Int): View? {
        val index = navItems.indexOfFirst { it.id == itemId }
        return itemViews.getOrNull(index)
    }

    /** Selected-item highlight: a plain fully-rounded pill in a soft
     *  gold tint — symmetric, no shadow, same shape on every tab. */
    private fun makeBlobBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(activeTint, 46))
            cornerRadius = itemHeightPx / 2f
        }
    }

    private fun refreshItemBackgrounds() {
        itemViews.forEachIndexed { index, view ->
            val shouldHave = index == selectedIndex
            if (shouldHave && view.background == null) {
                view.background = makeBlobBackground()
            } else if (!shouldHave && view.background != null) {
                view.background = null
            }
        }
    }

    private fun applySelection(animate: Boolean) {
        val targets = IntArray(navItems.size) { index ->
            if (showLabels && index == selectedIndex) expandedWidths[index] else collapsedWidthPx
        }
        widthAnimator?.cancel()
        refreshItemBackgrounds()
        // Icon-only: centre the icon in its square cell; with labels the
        // icon+label group starts at the item padding.
        itemViews.forEachIndexed { index, view ->
            val centred = !showLabels || index != selectedIndex
            view.gravity = if (centred) Gravity.CENTER else Gravity.CENTER_VERTICAL
            view.setPadding(if (centred) 0 else itemPadPx, 0, 0, 0)
            labelViews[index].visibility =
                if (showLabels && index == selectedIndex) View.VISIBLE else View.GONE
        }
        iconViews.forEachIndexed { index, icon ->
            icon.setColorFilter(if (index == selectedIndex) activeTint else inactiveTint)
        }
        if (!animate) {
            itemViews.forEachIndexed { index, view ->
                (view.layoutParams as LinearLayout.LayoutParams).width = targets[index]
                view.requestLayout()
            }
            labelViews.forEach { label ->
                label.alpha = 1f
                label.translationX = 0f
            }
            requestLayout()
            return
        }
        val starts = IntArray(navItems.size) { itemViews[it].width.takeIf { w -> w > 0 } ?: targets[it] }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 220
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { valueAnimator ->
            val fraction = valueAnimator.animatedValue as Float
            itemViews.forEachIndexed { index, view ->
                val width = starts[index] +
                    ((targets[index] - starts[index]) * fraction).toInt()
                val lp = view.layoutParams as LinearLayout.LayoutParams
                if (lp.width != width) {
                    lp.width = width
                    view.layoutParams = lp
                }
            }
            labelViews[selectedIndex].alpha = fraction
            itemViews[selectedIndex].background?.alpha = (fraction * 255).toInt()
            requestLayout()
        }
        widthAnimator = animator
        animator.start()
    }

    private fun itemIdAt(rawX: Float, rawY: Float): Int? {
        val location = IntArray(2)
        itemViews.forEachIndexed { index, view ->
            view.getLocationOnScreen(location)
            if (rawX >= location[0] && rawX < location[0] + view.width &&
                rawY >= location[1] && rawY < location[1] + view.height
            ) {
                return navItems[index].id
            }
        }
        return null
    }

    /** Drag horizontally across the bar to scrub between tabs — the same
     *  semantics as MainActivity.setupNavScrub on the stock bar: a move
     *  past the threshold starts scrubbing, crossing into another item
     *  selects it through the owner, and a scrub that changed the
     *  selection consumes the release so no click fires. */
    private fun handleScrubTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubDownX = event.rawX
                scrubDownY = event.rawY
                scrubbing = false
                scrubChangedSelection = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing &&
                    (abs(event.rawX - scrubDownX) > scrubThresholdPx ||
                        abs(event.rawY - scrubDownY) > scrubThresholdPx)
                ) {
                    scrubbing = true
                }
                if (scrubbing) {
                    val target = itemIdAt(event.rawX, event.rawY)
                    if (target != null && target != selectedItemId) {
                        onNavigateRequest?.invoke(target)
                        if (selectedItemId == target) scrubChangedSelection = true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = scrubbing && scrubChangedSelection
                scrubbing = false
                return consumed
            }
        }
        return false
    }

    companion object {
        const val NAV_LABELS_KEY = "nav_show_labels"
    }
}
