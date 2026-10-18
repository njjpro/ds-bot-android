package com.aiyoyo.dshlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 极小的界面工具集：不引入 AndroidX，全部走 framework API。 */
object Ui {

    fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, resId: Int): Int = ctx.getColor(resId)

    fun rounded(ctx: Context, fill: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun topRounded(ctx: Context, fill: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            val r = dp(ctx, radiusDp).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

    fun circle(ctx: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }

    /** 卡片背景 + 按压水波纹（与背景同形状，避免溢出圆角）。 */
    fun clickableCard(ctx: Context, fill: Int, radiusDp: Float): RippleDrawable {
        val shape = rounded(ctx, fill, radiusDp)
        val mask = rounded(ctx, 0xFFFFFFFF.toInt(), radiusDp)
        return RippleDrawable(ColorStateList.valueOf(color(ctx, R.color.ripple)), shape, mask)
    }

    fun text(
        ctx: Context,
        sizeSp: Float,
        colorInt: Int,
        bold: Boolean = false,
        text: String = ""
    ): TextView = TextView(ctx).apply {
        textSize = sizeSp
        setTextColor(colorInt)
        this.text = text
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    /** 44dp 的可点击图标按钮，用字形代替矢量图。 */
    fun iconButton(ctx: Context, glyph: String, contentDesc: String): TextView =
        text(ctx, 22f, color(ctx, R.color.text), false, glyph).apply {
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            val size = dp(ctx, 44f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(color(ctx, R.color.ripple)),
                null,
                circle(ctx, 0xFFFFFFFF.toInt())
            )
        }

    /** 顶栏里的圆形图标按钮。 */
    fun roundIconButton(ctx: Context, glyph: String, contentDesc: String): TextView =
        text(ctx, 19f, color(ctx, R.color.textDim), false, glyph).apply {
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            val size = dp(ctx, 40f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(color(ctx, R.color.ripple)),
                null,
                circle(ctx, 0xFFFFFFFF.toInt())
            )
        }

    fun primaryButton(ctx: Context, label: String): TextView =
        text(ctx, 14.5f, color(ctx, R.color.accentInk), true, label).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 24f), dp(ctx, 13f), dp(ctx, 24f), dp(ctx, 13f))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                rounded(ctx, color(ctx, R.color.accent), 14f),
                rounded(ctx, 0xFFFFFFFF.toInt(), 14f)
            )
            isClickable = true
            isFocusable = true
        }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
        )
    }

    private val AVATAR_COLORS = intArrayOf(
        0xFF34D399.toInt(), 0xFF60A5FA.toInt(), 0xFFF472B6.toInt(),
        0xFFFBBF24.toInt(), 0xFFA78BFA.toInt(), 0xFF22D3EE.toInt(),
        0xFFFB923C.toInt(), 0xFF4ADE80.toInt()
    )

    fun avatarColor(seed: String): Int {
        var hash = 0
        for (ch in seed) hash = hash * 31 + ch.code
        return AVATAR_COLORS[(hash and Int.MAX_VALUE) % AVATAR_COLORS.size]
    }

    fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val first = trimmed.codePointAt(0)
        return String(Character.toChars(first)).uppercase()
    }
}
