package com.lagradost.cloudstream3.ui.settings.extensions

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentPluginsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.CommonActivity.showToast

const val PLUGINS_BUNDLE_DATA = "data"
const val PLUGINS_BUNDLE_LOCAL = "isLocal"

class PluginsFragment : BaseFragment<FragmentPluginsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentPluginsBinding::inflate)
) {
    private lateinit var pluginViewModel: PluginsViewModel

    override fun onDestroyView() {
        pluginViewModel.clear() // clear for the next observe
        super.onDestroyView()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentPluginsBinding) {
        pluginViewModel = ViewModelProvider(this)[PluginsViewModel::class.java]

        // Since the ViewModel is getting reused the tvTypes must be cleared between uses
        pluginViewModel.tvTypes.clear()
        pluginViewModel.selectedLanguages = listOf()
        pluginViewModel.clear()

        // Filter by language set on preferred media
        activity?.let {
            val providerLangs = it.getApiProviderLangSettings().toList()
            if (!providerLangs.contains(AllLanguagesName)) {
                pluginViewModel.selectedLanguages = mutableListOf("none") + providerLangs
            }
        }

        val repositoryData = arguments?.getString(PLUGINS_BUNDLE_DATA)?.let { data ->
            tryParseJson<RepositoryData>(data)
        }
        val isLocal = arguments?.getBoolean(PLUGINS_BUNDLE_LOCAL) == true
        // download all extensions button
        val downloadAllButton = binding.settingsToolbar.menu?.findItem(R.id.download_all)

        if (repositoryData == null) {
            dispatchBackPressed()
            return
        }

        setToolBarScrollFlags()
        setUpToolbar(repositoryData.name)
        binding.settingsToolbar.apply {
            setOnMenuItemClickListener { menuItem ->
                when (menuItem?.itemId) {
                    R.id.select_all -> {
                        val adapter = binding.pluginRecyclerView.adapter as? PluginAdapter
                        if (adapter != null) {
                            val all = currentPlugins
                            adapter.selectAll(all, adapter.selectedKeys.size != all.size)
                        }
                    }

                    R.id.download_all -> {
                        PluginsViewModel.downloadAll(activity, repositoryData, pluginViewModel)
                    }

                    R.id.lang_filter -> {
                        val languagesTagName = pluginViewModel.pluginLanguages
                            .map { langTag ->
                                Pair(
                                    langTag,
                                    getNameNextToFlagEmoji(langTag) ?: langTag
                                )
                            }
                            .sortedBy {
                                it.second.substringAfter("\u00a0").lowercase()
                            } // name ignoring flag emoji
                            .toMutableList()

                        // Move "none" to 1st position as it's special code to indicate unknown/missing language
                        if (languagesTagName.remove(Pair("none", "none"))) {
                            languagesTagName.add(0, Pair("none", getString(R.string.no_data)))
                        }

                        val currentIndexList = pluginViewModel.selectedLanguages.map { langTag ->
                            languagesTagName.indexOfFirst { lang -> lang.first == langTag }
                        }

                        activity?.showMultiDialog(
                            languagesTagName.map { it.second },
                            currentIndexList,
                            getString(R.string.provider_lang_settings),
                            {}
                        ) { selectedList ->
                            pluginViewModel.selectedLanguages =
                                selectedList.map { languagesTagName[it].first }
                            pluginViewModel.updateFilteredPlugins()
                        }
                    }

                    else -> {}
                }
                return@setOnMenuItemClickListener true
            }

            val searchView =
                menu?.findItem(R.id.search_button)?.actionView as? SearchView

            // Don't go back if active query
            setNavigationOnClickListener {
                val adapter = binding.pluginRecyclerView.adapter as? PluginAdapter
                if (adapter?.selectionMode == true) {
                    adapter.setSelection(false)
                } else if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }
//        searchView?.onActionViewCollapsed = {
//            pluginViewModel.search(null)
//        }

        // Because onActionViewCollapsed doesn't wanna work we need this workaround :(

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter {
                    pluginViewModel.handlePluginAction(activity, listOf(repositoryData), it, isLocal)
                }
        }

        if (isLayout(TV or EMULATOR)) {
            // Scrolling down does not reveal the whole RecyclerView on TV, add to bypass that.
            binding.pluginRecyclerView.setPadding(0, 0, 0, 200.toPx)
        }

        setupBulkActions(binding, repositoryData, isLocal)

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            currentPlugins = list
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            updateBulkUi(binding)
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        if (isLocal) {
            // No download button and no categories on local
            downloadAllButton?.isVisible = false
            binding.settingsToolbar.menu?.findItem(R.id.lang_filter)?.isVisible = false
            pluginViewModel.updatePluginListLocal()

            binding.tvtypesChipsScroll.root.isVisible = false
        } else {
            pluginViewModel.updatePluginList(context, listOf(repositoryData))
            binding.tvtypesChipsScroll.root.isVisible = true
            // not needed for users but may be useful for devs
            downloadAllButton?.isVisible = BuildConfig.DEBUG

            bindChips(
                binding.tvtypesChipsScroll.tvtypesChips,
                emptyList(),
                TvType.entries.toList(),
                callback = { list ->
                    pluginViewModel.tvTypes.clear()
                    pluginViewModel.tvTypes.addAll(list.map { it.name })
                    pluginViewModel.updateFilteredPlugins()
                },
                nextFocusDown = R.id.plugin_recycler_view,
                nextFocusUp = null,
            )
        }
    }

    // ---------------------------------------------------------------
    // CloudStream Dark: bulk install / remove (approved icon mockup)
    // ---------------------------------------------------------------
    private var currentPlugins: List<PluginViewData> = emptyList()

    private fun setupBulkActions(
        binding: FragmentPluginsBinding,
        repositoryData: RepositoryData,
        isLocal: Boolean,
    ) {
        val adapter = binding.pluginRecyclerView.adapter as? PluginAdapter ?: return
        val repos = listOf(repositoryData)
        binding.pluginSummary.isVisible = !isLayout(TV or EMULATOR)
        binding.pluginInstallAll.isVisible = !isLocal
        // Icon on the accent tile uses the colour meant for text on accent.
        binding.pluginInstallAll.imageTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getResourceColor(com.google.android.material.R.attr.colorOnPrimary)
        )

        binding.pluginInstallAll.setOnClickListener {
            val todo = currentPlugins.filter { !it.isDownloaded }.map { it.pluginWrapper }
            if (todo.isEmpty()) {
                showToast(R.string.plugins_nothing_to_install)
                return@setOnClickListener
            }
            pluginViewModel.batchAction(activity, repos, todo, install = true)
        }
        binding.pluginRemoveAll.setOnClickListener {
            val todo = currentPlugins.filter { it.isDownloaded }.map { it.pluginWrapper }
            if (todo.isEmpty()) {
                showToast(R.string.plugins_nothing_to_remove)
                return@setOnClickListener
            }
            confirmRemove(todo.size) {
                pluginViewModel.batchAction(activity, repos, todo, install = false)
            }
        }
        binding.pluginSelectMode.setOnClickListener { adapter.setSelection(true) }

        binding.pluginSelInstall.setOnClickListener {
            val todo = selectedItems(adapter).filter { !it.isDownloaded }.map { it.pluginWrapper }
            if (todo.isEmpty()) return@setOnClickListener
            pluginViewModel.batchAction(activity, repos, todo, install = true)
            adapter.setSelection(false)
        }
        binding.pluginSelRemove.setOnClickListener {
            val todo = selectedItems(adapter).filter { it.isDownloaded }.map { it.pluginWrapper }
            if (todo.isEmpty()) return@setOnClickListener
            confirmRemove(todo.size) {
                pluginViewModel.batchAction(activity, repos, todo, install = false)
                adapter.setSelection(false)
            }
        }
        adapter.onSelectionChanged = { updateBulkUi(binding) }

        // System back leaves selection mode first.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    adapter.setSelection(false)
                }
            }.also { cb -> selectionBackCallback = cb }
        )

        with(com.lagradost.cloudstream3.ui.FloatingNavInsets) {
            binding.pluginSelectionBar.followFloatingNav { h ->
                setBottomMarginPx(h + 12.toPx)
            }
        }
    }

    private var selectionBackCallback: androidx.activity.OnBackPressedCallback? = null

    private fun selectedItems(adapter: PluginAdapter) =
        currentPlugins.filter { adapter.selectedKeys.contains(adapter.keyOf(it)) }

    private fun confirmRemove(count: Int, onYes: () -> Unit) {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.plugins_remove_confirm_title)
            .setMessage(getString(R.string.plugins_remove_confirm_message, count))
            .setPositiveButton(R.string.delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateBulkUi(binding: FragmentPluginsBinding) {
        val adapter = binding.pluginRecyclerView.adapter as? PluginAdapter ?: return
        val total = currentPlugins.size
        val installed = currentPlugins.count { it.isDownloaded }
        binding.pluginSummaryTitle.text = getString(R.string.plugins_summary_title, total)
        binding.pluginSummarySub.text =
            getString(R.string.plugins_summary_sub, installed, total - installed)

        val selecting = adapter.selectionMode
        val menu = binding.settingsToolbar.menu
        menu?.findItem(R.id.select_all)?.isVisible = selecting
        menu?.findItem(R.id.search_button)?.isVisible = !selecting
        menu?.findItem(R.id.lang_filter)?.isVisible =
            !selecting && arguments?.getBoolean(PLUGINS_BUNDLE_LOCAL) != true
        selectionBackCallback?.isEnabled = selecting
        binding.pluginSelectionBar.isVisible = selecting
        binding.pluginSummary.isVisible = !selecting && !isLayout(TV or EMULATOR)
        if (selecting) {
            binding.settingsToolbar.title =
                getString(R.string.plugins_selected_count, adapter.selectedKeys.size)
            binding.settingsToolbar.setNavigationIcon(R.drawable.ic_baseline_close_24)
            val sel = selectedItems(adapter)
            val toInstall = sel.count { !it.isDownloaded }
            val toRemove = sel.count { it.isDownloaded }
            binding.pluginSelInstallCount.text = toInstall.toString()
            binding.pluginSelRemoveCount.text = toRemove.toString()
            binding.pluginSelInstall.alpha = if (toInstall > 0) 1f else 0.4f
            binding.pluginSelRemove.alpha = if (toRemove > 0) 1f else 0.4f
            val allSelected = adapter.selectedKeys.size == total && total > 0
            menu?.findItem(R.id.select_all)?.icon?.mutate()?.setTint(
                requireContext().getResourceColor(
                    if (allSelected) androidx.appcompat.R.attr.colorPrimary else R.attr.iconColor
                )
            )
        } else {
            arguments?.getString(PLUGINS_BUNDLE_DATA)?.let { tryParseJson<RepositoryData>(it) }
                ?.let { binding.settingsToolbar.title = it.name }
            binding.settingsToolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
        }
    }

    companion object {
        fun newInstance(repositoryData: RepositoryData): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, repositoryData.toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, false)
            }
        }
         fun newLocalInstance(name: String): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, RepositoryData("", name, "").toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, true)
            }
        }
    }
}