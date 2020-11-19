package com.jamal2367.styx.browser.tabs

import com.jamal2367.styx.R
import com.jamal2367.styx.browser.activity.BrowserActivity
import com.jamal2367.styx.controller.UIController
import com.jamal2367.styx.view.BackgroundDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.tab_list_item.view.*

/**
 * The [RecyclerView.ViewHolder] for both vertical and horizontal tabs.
 * That represents an item in our list, basically one tab.
 */
class TabViewHolder(
    view: View,
    private val uiController: UIController
) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnLongClickListener, ItemTouchHelperViewHolder {

    val txtTitle: TextView = view.textTab
    val favicon: ImageView = view.faviconTab
    // Need to keep findViewById to avoid crash when using desktop style tabs
    // Maybe this could be fixed by unifying both layout?
    val exitButton: View = view.findViewById(R.id.deleteAction)
    val layout: LinearLayout = view.tab_item_background

    private var previousBackground: BackgroundDrawable? = null

    init {
        exitButton.setOnClickListener(this)
        layout.setOnClickListener(this)
        layout.setOnLongClickListener(this)
        // Is that the best way to access our preferences?
        exitButton.visibility = if ((view.context as BrowserActivity).userPreferences.showCloseTabButton) View.VISIBLE else View.GONE
    }

    override fun onClick(v: View) {
        if (v === exitButton) {
            uiController.tabCloseClicked(adapterPosition)
        } else if (v === layout) {
            uiController.tabClicked(adapterPosition)
        }
    }

    override fun onLongClick(v: View): Boolean {
        //uiController.showCloseDialog(adapterPosition)
        //return true
        return false
    }

    // From ItemTouchHelperViewHolder
    // Start dragging
    override fun onItemSelected() {
        // Do some fancy for smoother transition
        previousBackground = layout.background as BackgroundDrawable
        previousBackground?.let {
                layout.background = BackgroundDrawable(itemView.context, if (it.isSelected)  R.attr.selectedBackground else R.attr.colorPrimaryDark, R.attr.colorControlHighlight).apply{startTransition(300)}
            }
        }

    // From ItemTouchHelperViewHolder
    // Stopped dragging
    override fun onItemClear() {
        // Here sadly no transition
        layout.background = previousBackground
    }


}
