package com.lagradost.cloudstream3.ui

import android.view.View
import android.view.ViewGroup

/**
 * CloudStream Dark: the floating bottom bar's real on-screen height
 * (bar + its lift + the system navigation bar inset), published by
 * [FloatingNavView]. Screens with content pinned to the bottom (Library
 * tabs, Downloads queue bar, their FABs) follow it instead of a fixed
 * dp guess, which ignored the 3-button navigation bar and let the bar
 * cover them. 0 = no floating bar (TV, landscape rail): layouts keep
 * their XML values.
 */
object FloatingNavInsets {
    @Volatile
    var heightPx: Int = 0
        private set

    private val listeners = linkedSetOf<(Int) -> Unit>()

    fun update(height: Int) {
        if (height == heightPx) return
        heightPx = height
        listeners.toList().forEach { it(height) }
    }

    /**
     * Calls [apply] now and whenever the bar height changes, while this
     * view is attached. [apply] only runs for a visible bar (height > 0).
     */
    fun View.followFloatingNav(apply: View.(navHeight: Int) -> Unit) {
        val listener: (Int) -> Unit = { h -> if (h > 0) post { apply(h) } }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                listeners.add(listener)
                listener(heightPx)
            }

            override fun onViewDetachedFromWindow(v: View) {
                listeners.remove(listener)
            }
        })
        if (isAttachedToWindow) {
            listeners.add(listener)
            listener(heightPx)
        }
    }

    fun View.setBottomMarginPx(px: Int) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin == px) return
        lp.bottomMargin = px
        layoutParams = lp
    }

    fun View.setBottomPaddingPx(px: Int) {
        if (paddingBottom == px) return
        setPadding(paddingLeft, paddingTop, paddingRight, px)
    }
}
