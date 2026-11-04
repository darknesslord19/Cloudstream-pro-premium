package com.lagradost.cloudstream3.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentHomeCustomListsBinding
import com.lagradost.cloudstream3.databinding.HomeCustomListRowBinding

/** Phase 8.44: overview of the user's custom home lists. */
class HomeCustomListsFragment : Fragment() {

    private var binding: FragmentHomeCustomListsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentHomeCustomListsBinding.inflate(
            inflater, container, false
        )
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Phase 8.47: status-bar inset — these are plain
        // Fragments, so no BaseFragment fixLayout keeps the
        // title / name field from sliding under the status bar.
        val insetTarget = view
        val baseTop = insetTarget.paddingTop
        val baseBottom = insetTarget.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(insetTarget) { v, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
            ).top
            // Keep the Save button above the system navigation bar and
            // the keyboard (it used to sit hidden behind the nav buttons).
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.setPadding(
                v.paddingLeft, baseTop + top,
                v.paddingRight, baseBottom + bottom
            )
            insets
        }
        insetTarget.requestApplyInsets()
        super.onViewCreated(view, savedInstanceState)
        binding?.customListsRecycler?.layoutManager =
            LinearLayoutManager(requireContext())
        binding?.customListsBack?.setOnClickListener {
            findNavController().navigateUp()
        }
        binding?.customListsNew?.setOnClickListener {
            // No arguments: the destination's listId defaults to
            // null. (Passing an explicit null in a bundle crashed
            // the nav runtime — wrong argument type in savedState.)
            findNavController().navigate(
                R.id.action_home_custom_lists_to_edit
            )
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val ctx = requireContext()
        val lists = HomeCustomLists.getLists(ctx)
        val activeId = HomeCustomLists.activeId(ctx)
        b.customListsEmpty.visibility =
            if (lists.isEmpty()) View.VISIBLE else View.GONE
        b.customListsRecycler.adapter = ListsAdapter(
            lists = lists,
            activeId = activeId,
            onEdit = { list ->
                findNavController().navigate(
                    R.id.action_home_custom_lists_to_edit,
                    bundleOf("listId" to list.id)
                )
            },
            onUse = { list ->
                if (HomeCustomLists.activeId(ctx) == list.id) {
                    HomeCustomLists.setActiveId(ctx, null)
                } else {
                    HomeCustomLists.setActiveId(ctx, list.id)
                    Toast.makeText(
                        ctx,
                        getString(R.string.home_custom_now_active, list.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refresh()
            },
            onDelete = { list ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.home_custom_delete)
                    .setMessage(
                        getString(R.string.home_custom_delete_confirm, list.name)
                    )
                    .setPositiveButton(R.string.home_custom_delete) { _, _ ->
                        HomeCustomLists.delete(ctx, list.id)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private class ListsAdapter(
        private val lists: List<HomeCustomLists.CustomList>,
        private val activeId: String?,
        private val onEdit: (HomeCustomLists.CustomList) -> Unit,
        private val onUse: (HomeCustomLists.CustomList) -> Unit,
        private val onDelete: (HomeCustomLists.CustomList) -> Unit
    ) : RecyclerView.Adapter<ListsAdapter.Holder>() {

        class Holder(val b: HomeCustomListRowBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): Holder = Holder(
            HomeCustomListRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun getItemCount(): Int = lists.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val list = lists[position]
            val b = holder.b
            val ctx = b.root.context
            val active = list.id == activeId
            b.customRowName.text = list.name
            // "20 kategori · 5 eklenti": categories and the number of
            // distinct providers (extensions) they come from.
            b.customRowMeta.text = ctx.getString(
                R.string.home_custom_meta,
                list.entries.size,
                list.entries.map { it.apiName }.distinct().size
            )
            val accent = ctx.getResourceColor(androidx.appcompat.R.attr.colorPrimary)
            val onAccent = ctx.getResourceColor(com.google.android.material.R.attr.colorOnPrimary)
            b.customRowCard.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20.toPx.toFloat()
                setColor(ctx.getResourceColor(R.attr.primaryGrayBackground))
                setStroke(
                    1.toPx,
                    if (active) androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 115)
                    else Color.argb(26, 255, 255, 255)
                )
            }
            b.customRowIcon.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12.toPx.toFloat()
                setColor(
                    if (active) androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 41)
                    else Color.argb(20, 255, 255, 255)
                )
            }
            b.customRowIcon.imageTintList = ColorStateList.valueOf(
                if (active) accent else Color.rgb(0xC7, 0xC7, 0xCC)
            )
            b.customRowUse.setText(
                if (active) R.string.home_custom_in_use
                else R.string.home_custom_use
            )
            b.customRowUse.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(51, 0, 0, 0)),
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14.toPx.toFloat()
                    if (active) {
                        setColor(Color.TRANSPARENT)
                        setStroke(1.toPx, androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 115))
                    } else setColor(accent)
                },
                null
            )
            b.customRowUse.setTextColor(if (active) accent else onAccent)
            b.customRowUse.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (active) R.drawable.ic_baseline_check_24 else 0, 0, 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(
                b.customRowUse, ColorStateList.valueOf(accent)
            )
            b.customRowEdit.contentDescription =
                ctx.getString(R.string.home_custom_edit) + ": " + list.name
            b.customRowDelete.contentDescription =
                ctx.getString(R.string.home_custom_delete) + ": " + list.name
            b.customRowEdit.setOnClickListener { onEdit(list) }
            b.customRowUse.setOnClickListener { onUse(list) }
            b.customRowDelete.setOnClickListener { onDelete(list) }
        }

    }
}
