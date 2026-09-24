package com.pandorasbox.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Keeps Spinner selection semantics while presenting choices in a readable bottom sheet. */
class OptionSpinner(context: Context, attrs: AttributeSet?) : AppCompatSpinner(context, attrs) {
    override fun performClick(): Boolean {
        val options = adapter ?: return super.performClick()
        if (options.count == 0) return true
        val title = contentDescription?.takeIf { it.isNotBlank() } ?: "Choose an option"

        val dialog = BottomSheetDialog(context)
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            background = ContextCompat.getDrawable(context, R.drawable.bg_choice_sheet)
        }

        sheet.addView(TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(18))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (index in 0 until options.count) {
            val selected = index == selectedItemPosition
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(54)
                setPadding(dp(16), 0, dp(16), 0)
                background = if (selected) GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_dark))
                    cornerRadius = dp(12).toFloat()
                } else {
                    val value = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    ContextCompat.getDrawable(context, value.resourceId)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setSelection(index)
                    dialog.dismiss()
                }
            }
            row.addView(TextView(context).apply {
                text = options.getItem(index)?.toString().orEmpty()
                setTextColor(ContextCompat.getColor(context, if (selected) R.color.accent_blue else R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selected) row.addView(TextView(context).apply {
                text = "✓"
                setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(options.count * dp(54), (resources.displayMetrics.heightPixels * 0.58f).toInt())
        ))
        dialog.setContentView(sheet)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
