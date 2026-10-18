package com.aiyoyo.dshlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 底部操作面板：长按实例后弹出。 */
class ActionSheet(
    private val host: Activity,
    title: String,
    private val actions: List<Action>
) : Dialog(host) {

    data class Action(val label: String, val danger: Boolean = false, val run: () -> Unit)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ctx = host
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRounded(ctx, Ui.color(ctx, R.color.surface), 26f)
            setPadding(0, Ui.dp(ctx, 10f), 0, Ui.dp(ctx, 18f))
        }
        root.addView(View(ctx).apply {
            setBackgroundColor(Ui.color(ctx, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 38f), Ui.dp(ctx, 4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        root.addView(Ui.text(ctx, 13f, Ui.color(ctx, R.color.textDim), false, title).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 16f), 0, Ui.dp(ctx, 8f))
        })
        actions.forEach { action ->
            root.addView(row(ctx, action.label, if (action.danger) R.color.danger else R.color.text) {
                dismiss()
                action.run()
            })
        }
        root.addView(View(ctx).apply {
            setBackgroundColor(Ui.color(ctx, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1f)
            ).apply { topMargin = Ui.dp(ctx, 6f) }
        })
        root.addView(row(ctx, "取消", R.color.textDim) { dismiss() })
        setContentView(root)
    }

    private fun row(ctx: Context, label: String, colorRes: Int, onClick: () -> Unit): TextView =
        Ui.text(ctx, 16f, Ui.color(ctx, colorRes), false, label).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Ui.color(ctx, R.color.ripple)),
                null,
                null
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 54f)
            )
        }

    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
    }
}

/** 添加 / 编辑地址的居中卡片。 */
class EditorDialog(
    private val host: Activity,
    private val existing: Instance?,
    prefillUrl: String?,
    private val onSave: (String, String) -> Unit
) : Dialog(host) {

    private val nameInput: EditText
    private val urlInput: EditText

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ctx = host
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, Ui.color(ctx, R.color.surface), 22f)
            setPadding(
                Ui.dp(ctx, 22f), Ui.dp(ctx, 20f),
                Ui.dp(ctx, 22f), Ui.dp(ctx, 16f)
            )
        }

        root.addView(
            Ui.text(
                ctx, 18f, Ui.color(ctx, R.color.text), true,
                if (existing == null) "添加 DSH 地址" else "编辑 DSH 地址"
            )
        )
        root.addView(
            Ui.text(
                ctx, 12.5f, Ui.color(ctx, R.color.textDim), false,
                "粘贴 dsh web 打印的完整地址，token 会自动保存。"
            ).apply { setPadding(0, Ui.dp(ctx, 6f), 0, 0) }
        )

        nameInput = input(ctx, "名称（可选）", existing?.name ?: "", false)
        urlInput = input(ctx, "https://主机/?token=…", existing?.url ?: prefillUrl.orEmpty(), true)

        root.addView(fieldLabel(ctx, "名称"))
        root.addView(nameInput)
        root.addView(fieldLabel(ctx, "地址"))
        root.addView(urlInput)

        val paste = Ui.text(ctx, 12.5f, Ui.color(ctx, R.color.accent), true, "从剪贴板粘贴").apply {
            setPadding(0, Ui.dp(ctx, 10f), 0, 0)
            isClickable = true
            setOnClickListener {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (text.isNullOrBlank()) {
                    android.widget.Toast.makeText(ctx, "剪贴板是空的", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    urlInput.setText(text.trim())
                }
            }
        }
        root.addView(paste)

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 20f), 0, 0)
        }
        buttons.addView(Ui.text(ctx, 14.5f, Ui.color(ctx, R.color.textDim), false, "取消").apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 12f), Ui.dp(ctx, 18f), Ui.dp(ctx, 12f))
            isClickable = true
            setOnClickListener { dismiss() }
        })
        buttons.addView(Ui.primaryButton(ctx, "保存").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(ctx, 8f) }
            setOnClickListener { submit() }
        })
        root.addView(buttons)

        setContentView(ScrollView(ctx).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun fieldLabel(ctx: Context, label: String): TextView =
        Ui.text(ctx, 12f, Ui.color(ctx, R.color.textDim), false, label).apply {
            setPadding(0, Ui.dp(ctx, 16f), 0, Ui.dp(ctx, 6f))
        }

    private fun input(ctx: Context, hintText: String, value: String, multiline: Boolean): EditText =
        EditText(ctx).apply {
            hint = hintText
            setText(value)
            setTextColor(Ui.color(ctx, R.color.text))
            setHintTextColor(Ui.color(ctx, R.color.textDim))
            textSize = 15f
            background = Ui.rounded(ctx, Ui.color(ctx, R.color.surfaceAlt), 12f)
            setPadding(
                Ui.dp(ctx, 14f), Ui.dp(ctx, 12f),
                Ui.dp(ctx, 14f), Ui.dp(ctx, 12f)
            )
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                minLines = 2
                maxLines = 4
                setSingleLine(false)
            }
        }

    private fun submit() {
        val url = UrlTools.normalize(urlInput.text.toString())
        if (url == null) {
            urlInput.error = "地址无效，需要 http(s)://主机/… 形式"
            urlInput.requestFocus()
            return
        }
        val name = nameInput.text.toString().trim().ifEmpty { UrlTools.defaultName(url) }
        dismiss()
        onSave(name, url)
    }

    override fun show() {
        super.show()
        val metrics = host.resources.displayMetrics
        val width = (metrics.widthPixels - Ui.dp(host, 44f)).coerceAtMost(Ui.dp(host, 440f))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        nameInput.requestFocus()
    }
}
