package com.pr0gramm.app.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.pr0gramm.app.util.dip2px

class SpacingItemDecoration(private val dp: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.setEmpty()

        val index = parent.getChildAdapterPosition(view)
        if (index == 0) {
            outRect.top = parent.context.dip2px(dp.toFloat()).toInt()
        }

        val itemCount = parent.adapter?.itemCount ?: 0
        if (index == itemCount - 1) {
            outRect.bottom = parent.context.dip2px(dp.toFloat()).toInt()
        }
    }
}