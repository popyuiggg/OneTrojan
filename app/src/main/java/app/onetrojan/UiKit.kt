package app.onetrojan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

internal object UiKit {
    const val BACKGROUND = "#07111F"
    const val SURFACE = "#0D1C31"
    const val SURFACE_RAISED = "#122540"
    const val BORDER = "#294566"
    const val ORANGE = "#FF5A2A"
    const val ORANGE_DARK = "#D93B16"
    const val TEXT = "#F7F4F0"
    const val TEXT_MUTED = "#A9B8CA"
    const val ERROR = "#FF7B72"

    fun color(value: String): Int = Color.parseColor(value)

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun rounded(
        color: String,
        radius: Float,
        strokeColor: String? = null,
        strokeWidth: Int = 0,
    ) = GradientDrawable().apply {
        setColor(UiKit.color(color))
        cornerRadius = radius
        if (strokeColor != null && strokeWidth > 0) {
            setStroke(strokeWidth, UiKit.color(strokeColor))
        }
    }

    fun stylePrimaryButton(button: Button) = button.apply {
        isAllCaps = false
        textSize = 17f
        setTextColor(color(TEXT))
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(ORANGE, context.dp(18).toFloat())
        setPadding(context.dp(20), 0, context.dp(20), 0)
        minHeight = context.dp(58)
        elevation = context.dp(4).toFloat()
    }

    fun styleSecondaryButton(button: Button) = button.apply {
        isAllCaps = false
        textSize = 16f
        setTextColor(color(TEXT))
        background = rounded(SURFACE_RAISED, context.dp(18).toFloat(), BORDER, context.dp(1))
        setPadding(context.dp(18), 0, context.dp(18), 0)
        minHeight = context.dp(56)
        elevation = 0f
    }

    fun styleCompactButton(button: Button) = button.apply {
        isAllCaps = false
        textSize = 14f
        setTextColor(color(ORANGE))
        background = rounded(SURFACE_RAISED, context.dp(12).toFloat(), BORDER, context.dp(1))
        setPadding(context.dp(14), 0, context.dp(14), 0)
        minHeight = context.dp(44)
        elevation = 0f
    }

    fun styleInput(field: EditText) = field.apply {
        textSize = 16f
        setTextColor(color(TEXT))
        setHintTextColor(color(TEXT_MUTED))
        background = rounded(SURFACE, context.dp(14).toFloat(), BORDER, context.dp(1))
        setPadding(context.dp(16), 0, context.dp(16), 0)
        minHeight = context.dp(54)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        backgroundTintList = null
    }

    fun styleLabel(label: TextView) = label.apply {
        textSize = 14f
        setTextColor(color(TEXT_MUTED))
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    fun matchParent(height: Int): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height,
    )

    fun tint(states: Array<IntArray>, colors: IntArray) = ColorStateList(states, colors)

    fun setSystemBars(activity: android.app.Activity) {
        activity.window.statusBarColor = color(BACKGROUND)
        activity.window.navigationBarColor = color(BACKGROUND)
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = 0
    }

    fun View.verticalMargins(top: Int = 0, bottom: Int = 0): ViewGroup.MarginLayoutParams {
        return (layoutParams as? ViewGroup.MarginLayoutParams
            ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )).apply {
            topMargin = top
            bottomMargin = bottom
        }
    }
}
