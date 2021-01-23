package com.jamal2367.styx.browser.tabs

import com.jamal2367.styx.R
import com.jamal2367.styx.browser.activity.BrowserActivity
import com.jamal2367.styx.controller.UIController
import com.jamal2367.styx.extensions.*
import com.jamal2367.styx.utils.ItemDragDropSwipeAdapter
import com.jamal2367.styx.utils.ThemeUtils
import com.jamal2367.styx.utils.Utils
import com.jamal2367.styx.view.BackgroundDrawable
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.*

/**
 * The adapter for horizontal desktop style browser tabs.
 */
class TabsDesktopAdapter(
    context: Context,
    private val resources: Resources,
    private val uiController: UIController
) : RecyclerView.Adapter<TabViewHolder>(), ItemDragDropSwipeAdapter {

    private var tabList: List<TabViewState> = emptyList()
    private var textColor = Color.TRANSPARENT

    init {
        //val backgroundColor = Utils.mixTwoColors(ThemeUtils.getPrimaryColor(context), Color.BLACK, 0.75f)
        //val foregroundColor = ThemeUtils.getPrimaryColor(context)
    }

    fun showTabs(tabs: List<TabViewState>) {
        val oldList = tabList
        tabList = tabs

        DiffUtil.calculateDiff(TabViewStateDiffCallback(oldList, tabList)).dispatchUpdatesTo(this)
    }

    /**
     * From [RecyclerView.Adapter]
     */
    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): TabViewHolder {
        val view = viewGroup.context.inflater.inflate(R.layout.tab_list_item_horizontal, viewGroup, false)
        view.background = BackgroundDrawable(view.context)
        //val tab = tabList[i]
        return TabViewHolder(view, uiController)
    }

    /**
     * From [RecyclerView.Adapter]
     */
    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.exitButton.tag = position

        val tab = tabList[position]

        holder.txtTitle.text = tab.title
        updateViewHolderAppearance(holder, tab)
        updateViewHolderFavicon(holder, tab.favicon)
        // Update our copy so that we can check for changes then
        holder.tab = tab.copy();
    }

    /**
     * From [RecyclerView.Adapter]
     */
    override fun onViewRecycled(holder: TabViewHolder) {
        super.onViewRecycled(holder)
        // I'm not convinced that's needed
        //(uiController as BrowserActivity).toast("Recycled: " + holder.tab.title)
        holder.tab = TabViewState()
    }

    private fun updateViewHolderFavicon(viewHolder: TabViewHolder, favicon: Bitmap?) {
        favicon?.let {
                viewHolder.favicon.setImageBitmap(it)
            }
        ?: viewHolder.favicon.setImageResource(R.drawable.ic_webpage)
    }

    private fun updateViewHolderAppearance(viewHolder: TabViewHolder, tab: TabViewState) {

        // Just to init our default text color
        if (textColor == Color.TRANSPARENT) {
            textColor = viewHolder.txtTitle.currentTextColor
        }

        val context = viewHolder.layout.context

        if (tab.isForeground) {
            TextViewCompat.setTextAppearance(viewHolder.txtTitle, R.style.boldText)
            val newTextColor = (uiController as BrowserActivity).currentToolBarTextColor
            viewHolder.txtTitle.setTextColor(newTextColor)
            viewHolder.exitButton.findViewById<ImageView>(R.id.deleteButton).setColorFilter(newTextColor)
            //uiController.changeToolbarBackground(tab.favicon, tab.themeColor, viewHolder.layout.background)

            // If we just got to the foreground
            if (tab.isForeground!=viewHolder.tab.isForeground
                    // or if our theme color changed
                    || tab.themeColor!=viewHolder.tab.themeColor
                    // or if our theme color is different than our UI color, i.e. using favicon color instead of meta theme
                    || tab.themeColor!=uiController.getUiColor())
            {
                val backgroundColor = ThemeUtils.getColor(context, R.attr.selectedBackground)
                val foregroundColor = ThemeUtils.getColor(context, R.attr.colorPrimary)

                // Transition from background tab color to foreground tab color
                // That's just running a fancy animation
                viewHolder.layout.background =
                        BackgroundDrawable(context,
                                ColorDrawable(backgroundColor),
                                ColorDrawable(
                                        //If color mode activated
                                        if (uiController.isColorMode())
                                            if (tab.themeColor!=Color.TRANSPARENT)
                                            // Use meta theme color if we have one
                                                tab.themeColor
                                            else
                                                if (uiController.getUiColor()!=backgroundColor)
                                                // Use favicon extracted color if there is one
                                                    uiController.getUiColor()
                                                else
                                                // Otherwise use default foreground color
                                                    foregroundColor
                                        else // No color mode just use our theme default background then
                                            foregroundColor))
                                .apply { startTransition(250) }
            }

        } else {
            // Background tab
            TextViewCompat.setTextAppearance(viewHolder.txtTitle, R.style.italicText)
            viewHolder.txtTitle.setTextColor(textColor)
            viewHolder.exitButton.findViewById<ImageView>(R.id.deleteButton).setColorFilter(textColor)
            // Set background appropriate for background tab
            viewHolder.layout.background = BackgroundDrawable(viewHolder.layout.context)
        }

    }

    override fun getItemCount() = tabList.size

    /**
     * From [ItemDragDropSwipeAdapter]
     */
    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        // Note: recent tab list is not affected
        // Swap local list position
        Collections.swap(tabList, fromPosition, toPosition)
        // Swap model list position
        Collections.swap(uiController.getTabModel().allTabs, fromPosition, toPosition)
        // Tell base class an item was moved
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    /**
     * From [ItemDragDropSwipeAdapter]
     */
    override fun onItemDismiss(position: Int) {
        // Not used
    }

}
