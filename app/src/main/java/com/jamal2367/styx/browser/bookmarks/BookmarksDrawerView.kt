package com.jamal2367.styx.browser.bookmarks

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.ahmadaghazadeh.editor.widget.CodeEditor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamal2367.styx.R
import com.jamal2367.styx.adblock.allowlist.AllowListModel
import com.jamal2367.styx.animation.AnimationUtils
import com.jamal2367.styx.browser.BookmarksView
import com.jamal2367.styx.browser.TabsManager
import com.jamal2367.styx.browser.activity.BrowserActivity
import com.jamal2367.styx.controller.UIController
import com.jamal2367.styx.database.Bookmark
import com.jamal2367.styx.database.bookmark.BookmarkRepository
import com.jamal2367.styx.databinding.BookmarkDrawerViewBinding
import com.jamal2367.styx.di.DatabaseScheduler
import com.jamal2367.styx.di.MainScheduler
import com.jamal2367.styx.di.NetworkScheduler
import com.jamal2367.styx.di.injector
import com.jamal2367.styx.dialog.BrowserDialog
import com.jamal2367.styx.dialog.DialogItem
import com.jamal2367.styx.dialog.StyxDialogBuilder
import com.jamal2367.styx.extensions.color
import com.jamal2367.styx.extensions.drawable
import com.jamal2367.styx.extensions.inflater
import com.jamal2367.styx.extensions.setImageForTheme
import com.jamal2367.styx.favicon.FaviconModel
import com.jamal2367.styx.utils.isSpecialUrl
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The view that displays bookmarks in a list and some controls.
 */
class BookmarksDrawerView @JvmOverloads constructor(
        context: Context,
        private val activity: Activity,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), BookmarksView {

    @Inject internal lateinit var bookmarkModel: BookmarkRepository
    @Inject internal lateinit var allowListModel: AllowListModel
    @Inject internal lateinit var bookmarksDialogBuilder: StyxDialogBuilder
    @Inject internal lateinit var faviconModel: FaviconModel
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject @field:NetworkScheduler internal lateinit var networkScheduler: Scheduler
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler

    private val uiController: UIController

    // Adapter
    private var bookmarkAdapter: BookmarkListAdapter? = null

    // Colors
    private var scrollIndex: Int = 0

    private var bookmarksSubscription: Disposable? = null
    private var bookmarkUpdateSubscription: Disposable? = null

    private val uiModel = BookmarkUiModel()

    private var iBinding: BookmarkDrawerViewBinding

    init {
        context.injector.inject(this)
        uiController = context as UIController
        iBinding = BookmarkDrawerViewBinding.inflate(context.inflater,this, true)
        iBinding.uiController = uiController


        iBinding.bookmarkBackButton.setOnClickListener {
            if (!uiModel.isCurrentFolderRoot()) {
                setBookmarksShown(null, true)
                iBinding.bookmarkListView.layoutManager?.scrollToPosition(scrollIndex)
            }
        }

        findViewById<View>(R.id.action_page_tools).setOnClickListener { showPageToolsDialog(context) }

        bookmarkAdapter = BookmarkListAdapter(
            context,
            faviconModel,
            networkScheduler,
            mainScheduler,
            ::handleItemLongPress,
            ::handleItemClick
        )

        iBinding.bookmarkListView.let {
            it.layoutManager = LinearLayoutManager(context)
            it.adapter = bookmarkAdapter
        }

        setBookmarksShown(null, true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        bookmarksSubscription?.dispose()
        bookmarkUpdateSubscription?.dispose()

        bookmarkAdapter?.cleanupSubscriptions()
    }

    private fun getTabsManager(): TabsManager = uiController.getTabModel()

    // TODO: apply that logic to the add bookmark menu item from main pop-up menu
    // SL: I guess this is of no use here anymore since we removed the add bookmark button
    private fun updateBookmarkIndicator(url: String) {
        bookmarkUpdateSubscription?.dispose()
        bookmarkUpdateSubscription = bookmarkModel.isBookmark(url)
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { isBookmark ->
                bookmarkUpdateSubscription = null
                //addBookmarkView?.isSelected = isBookmark
                //addBookmarkView?.isEnabled = !url.isSpecialUrl()
            }
    }

    override fun handleBookmarkDeleted(bookmark: Bookmark) = when (bookmark) {
        is Bookmark.Folder -> setBookmarksShown(null, false)
        is Bookmark.Entry -> bookmarkAdapter?.deleteItem(BookmarksViewModel(bookmark)) ?: Unit
    }

    private fun setBookmarksShown(folder: String?, animate: Boolean) {
        bookmarksSubscription?.dispose()
        bookmarksSubscription = bookmarkModel.getBookmarksFromFolderSorted(folder)
            .concatWith(Single.defer {
                if (folder == null) {
                    bookmarkModel.getFoldersSorted()
                } else {
                    Single.just(emptyList())
                }
            })
            .toList()
            .map { it.flatten() }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { bookmarksAndFolders ->
                uiModel.currentFolder = folder
                setBookmarkDataSet(bookmarksAndFolders, animate)
            }
    }

    private fun setBookmarkDataSet(items: List<Bookmark>, animate: Boolean) {
        bookmarkAdapter?.updateItems(items.map { BookmarksViewModel(it) })
        val resource = if (uiModel.isCurrentFolderRoot()) {
            R.drawable.ic_bookmark_border
        } else {
            R.drawable.ic_action_back
        }

        if (animate) {
            iBinding.bookmarkBackButton.let {
                val transition = AnimationUtils.createRotationTransitionAnimation(it, resource)
                it.startAnimation(transition)
            }
        } else {
            iBinding.bookmarkBackButton.setImageResource(resource)
        }
    }

    private fun handleItemLongPress(bookmark: Bookmark): Boolean {
        (context as AppCompatActivity?)?.let {
            when (bookmark) {
                is Bookmark.Folder -> bookmarksDialogBuilder.showBookmarkFolderLongPressedDialog(it, uiController, bookmark)
                is Bookmark.Entry -> bookmarksDialogBuilder.showLongPressedDialogForBookmarkUrl(it, uiController, bookmark)
            }
        }
        return true
    }

    private fun handleItemClick(bookmark: Bookmark) = when (bookmark) {
        is Bookmark.Folder -> {
            scrollIndex = (iBinding.bookmarkListView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            setBookmarksShown(bookmark.title, true)
        }
        is Bookmark.Entry -> uiController.bookmarkItemClicked(bookmark)
    }


    /**
     * Show the page tools dialog.
     */
    private fun showPageToolsDialog(context: Context) {
        val currentTab = getTabsManager().currentTab ?: return
        val isAllowedAds = allowListModel.isUrlAllowedAds(currentTab.url)
        val whitelistString = if (isAllowedAds) {
            R.string.dialog_adblock_enable_for_site
        } else {
            R.string.dialog_adblock_disable_for_site
        }

        BrowserDialog.showWithIcons(context, context.getString(R.string.dialog_tools_title),
            DialogItem(
                icon = context.drawable(R.drawable.ic_block),
                colorTint = context.color(R.color.error_red).takeIf { isAllowedAds },
                title = whitelistString,
                isConditionMet = !currentTab.url.isSpecialUrl()
            ) {
                if (isAllowedAds) {
                    allowListModel.removeUrlFromAllowList(currentTab.url)
                } else {
                    allowListModel.addUrlToAllowList(currentTab.url)
                }
                getTabsManager().currentTab?.reload()
            },
                DialogItem(
                        icon = context.drawable(R.drawable.ic_baseline_code_24),
                        title = R.string.page_source

                ) {
                    val prefs: SharedPreferences = activity.getSharedPreferences("com.jamal2367.styx", MODE_PRIVATE)
                    var name: String? = prefs.getString("source", "Source could not be extracted")

                    // Hacky workaround for weird WebView encoding bug
                    name = name?.replace("\\u003C", "<")
                    name = name?.replace("\\n", System.getProperty("line.separator").toString())
                    name = name?.replace("\\t", "")
                    name = name?.replace("\\\"", "\"")
                    name = name?.substring(1, name.length - 1)
                    if (name?.contains("mod_pagespeed")!!) {
                        Toast.makeText(activity as AppCompatActivity, R.string.pagespeed_error, Toast.LENGTH_LONG).show()
                    }
                    val builder = MaterialAlertDialogBuilder(context)
                    val inflater = activity.layoutInflater
                    builder.setTitle(R.string.page_source_title)
                    val dialogLayout = inflater.inflate(R.layout.dialog_view_source, null)
                    val editText = dialogLayout.findViewById<CodeEditor>(R.id.dialog_multi_line)
                    editText.setText(name, 1)
                    builder.setView(dialogLayout)
                    builder.setPositiveButton("OK") { dialogInterface, i -> editText.setText(editText.text?.toString()?.replace("\'", "\\\'"), 1); currentTab.loadUrl("javascript:(function() { document.documentElement.innerHTML = '" + editText.text.toString() + "'; })()") }
                    builder.show()
                }
        )
    }

    override fun navigateBack() {
        if (uiModel.isCurrentFolderRoot()) {
            uiController.onBackButtonPressed()
        } else {
            setBookmarksShown(null, true)
            iBinding.bookmarkListView.layoutManager?.scrollToPosition(scrollIndex)
        }
    }

    override fun handleUpdatedUrl(url: String) {
        updateBookmarkIndicator(url)
        val folder = uiModel.currentFolder
        setBookmarksShown(folder, false)
    }

    private class BookmarkViewHolder(
        itemView: View,
        private val adapter: BookmarkListAdapter,
        private val onItemLongClickListener: (Bookmark) -> Boolean,
        private val onItemClickListener: (Bookmark) -> Unit
    ) : RecyclerView.ViewHolder(itemView), OnClickListener, OnLongClickListener {

        val txtTitle: TextView = itemView.findViewById(R.id.textBookmark)
        val favicon: ImageView = itemView.findViewById(R.id.faviconBookmark)

        init {
            itemView.setOnLongClickListener(this)
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val index = adapterPosition
            if (index.toLong() != RecyclerView.NO_ID) {
                onItemClickListener(adapter.itemAt(index).bookmark)
            }
        }

        override fun onLongClick(v: View): Boolean {
            val index = adapterPosition
            return index != RecyclerView.NO_POSITION && onItemLongClickListener(adapter.itemAt(index).bookmark)
        }
    }

    private class BookmarkListAdapter(
        val context: Context,
        private val faviconModel: FaviconModel,
        private val networkScheduler: Scheduler,
        private val mainScheduler: Scheduler,
        private val onItemLongClickListener: (Bookmark) -> Boolean,
        private val onItemClickListener: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkViewHolder>() {

        private var bookmarks: List<BookmarksViewModel> = listOf()
        private val faviconFetchSubscriptions = ConcurrentHashMap<String, Disposable>()
        private val folderIcon = context.drawable(R.drawable.ic_folder)
        private val webpageIcon = context.drawable(R.drawable.ic_webpage)

        fun itemAt(position: Int): BookmarksViewModel = bookmarks[position]

        fun deleteItem(item: BookmarksViewModel) {
            val newList = bookmarks - item
            updateItems(newList)
        }

        fun updateItems(newList: List<BookmarksViewModel>) {
            val oldList = bookmarks
            bookmarks = newList

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldList.size

                override fun getNewListSize() = bookmarks.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    oldList[oldItemPosition].bookmark.url == bookmarks[newItemPosition].bookmark.url

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    oldList[oldItemPosition] == bookmarks[newItemPosition]
            })

            diffResult.dispatchUpdatesTo(this)
        }

        fun cleanupSubscriptions() {
            for (subscription in faviconFetchSubscriptions.values) {
                subscription.dispose()
            }
            faviconFetchSubscriptions.clear()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemView = inflater.inflate(R.layout.bookmark_list_item, parent, false)

            return BookmarkViewHolder(itemView, this, onItemLongClickListener, onItemClickListener)
        }

        override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
            holder.itemView.jumpDrawablesToCurrentState()

            val viewModel = bookmarks[position]
            holder.txtTitle.text = viewModel.bookmark.title

            val url = viewModel.bookmark.url
            holder.favicon.tag = url

            viewModel.icon?.let {
                holder.favicon.setImageBitmap(it)
                return
            }

            val imageDrawable = when (viewModel.bookmark) {
                is Bookmark.Folder -> folderIcon
                is Bookmark.Entry -> webpageIcon.also {
                    faviconFetchSubscriptions[url]?.dispose()
                    faviconFetchSubscriptions[url] = faviconModel
                        .faviconForUrl(url, viewModel.bookmark.title)
                        .subscribeOn(networkScheduler)
                        .observeOn(mainScheduler)
                        .subscribeBy(
                            onSuccess = { bitmap ->
                                viewModel.icon = bitmap
                                if (holder.favicon.tag == url) {
                                    val ba = context as BrowserActivity
                                    holder.favicon.setImageForTheme(bitmap, ba.useDarkTheme)
                                }
                            }
                        )
                }
            }

            holder.favicon.setImageDrawable(imageDrawable)
        }

        override fun getItemCount() = bookmarks.size
    }

}
