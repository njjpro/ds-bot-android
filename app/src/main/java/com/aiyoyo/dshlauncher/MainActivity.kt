package com.aiyoyo.dshlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单 Activity：地址列表与多标签工作区共存。
 * 返回列表不会销毁 WebView，所以两个不同地址可以一直并行、切换即时。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_FILE = 1001
        private const val REQ_MIC = 1002
        private const val REQ_NOTIFY = 2001
        private const val PREFS = "dsh_launcher"
        private const val PREF_SECURE = "flag_secure"
        private const val AUTH_MARKER = "dsh web authentication required"
        private const val KEY_CONSOLE = "last_console_error"
        private const val MAX_LIVE = 4
        private const val CHANNEL_ID = "dsh_events"
        private const val REPO_RELEASES_API =
            "https://api.github.com/repos/njjpro/ds-bot-android/releases/latest"
    }

    private lateinit var store: InstanceStore

    // 列表
    private lateinit var listRoot: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sectionLabel: TextView
    private var items: MutableList<Instance> = mutableListOf()

    // 工作区
    private lateinit var workspaceRoot: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var authBar: TextView

    private val live = LinkedHashMap<String, WebView>(0, 0.75f, true)
    private val instances = HashMap<String, Instance>()
    private var currentId: String? = null
    private var inWorkspace = false
    private var foreground = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMicRequest: PermissionRequest? = null
    private var updateDownloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    private val currentInstance: Instance?
        get() = currentId?.let { instances[it] }

    private fun instanceOf(view: WebView): Instance? =
        (view.tag as? String)?.let { instances[it] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = InstanceStore(this)
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SECURE, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(buildRoot())
        reload()
        handleShare(intent)
        showCrashReportIfAny()
        requestNotificationPermission()
        intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }?.let {
            openTab(it)
            showWorkspace()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        intent?.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }?.let {
            openTab(it)
            showWorkspace()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!inWorkspace) reload()
    }

    // ------------------------------------------------------------- 根布局

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@MainActivity, R.color.bg))
        }
        listRoot = buildListRoot()
        workspaceRoot = buildWorkspace()
        workspaceRoot.visibility = View.GONE
        root.addView(listRoot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(workspaceRoot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    private fun showList() {
        inWorkspace = false
        workspaceRoot.visibility = View.GONE
        listRoot.visibility = View.VISIBLE
        reload()
    }

    private fun showWorkspace() {
        inWorkspace = true
        listRoot.visibility = View.GONE
        workspaceRoot.visibility = View.VISIBLE
    }

    // --------------------------------------------------------------- 列表

    private fun buildListRoot(): LinearLayout {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(ctx, R.color.bg))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 22f), Ui.dp(ctx, 18f), Ui.dp(ctx, 14f), Ui.dp(ctx, 4f))
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo)
            imageTintList = ColorStateList.valueOf(Ui.color(ctx, R.color.accent))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 30f), Ui.dp(ctx, 30f)).apply {
                rightMargin = Ui.dp(ctx, 10f)
            }
        }
        val title = Ui.text(ctx, 26f, Ui.color(ctx, R.color.text), true, "DS Bot")
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val add = Ui.text(ctx, 23f, Ui.color(ctx, R.color.accentInk), false, "＋").apply {
            gravity = Gravity.CENTER
            contentDescription = "添加地址"
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                Ui.circle(ctx, Ui.color(ctx, R.color.accent)),
                Ui.circle(ctx, 0xFFFFFFFF.toInt())
            )
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 42f), Ui.dp(ctx, 42f))
            setOnClickListener { openEditor(null) }
        }
        topRow.addView(logo)
        topRow.addView(title)
        topRow.addView(spacer)
        topRow.addView(add)
        header.addView(topRow)
        header.addView(
            Ui.text(ctx, 13f, Ui.color(ctx, R.color.textDim), false, "打开你的 DSH 工作台").apply {
                setPadding(Ui.dp(ctx, 40f), Ui.dp(ctx, 8f), 0, 0)
            }
        )

        sectionLabel = Ui.text(ctx, 12f, Ui.color(ctx, R.color.textDim), false, "最近使用").apply {
            letterSpacing = 0.08f
            setPadding(Ui.dp(ctx, 24f), Ui.dp(ctx, 22f), Ui.dp(ctx, 24f), Ui.dp(ctx, 6f))
            visibility = View.GONE
        }

        scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
            clipToPadding = false
        }
        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)

        emptyView = buildEmptyView()

        val footer = Ui.text(
            ctx, 11.5f, Ui.color(ctx, R.color.textDim), false,
            "地址里的 token 就是登录凭证，仅加密保存在本机。"
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(ctx, 24f), Ui.dp(ctx, 10f),
                Ui.dp(ctx, 24f), Ui.dp(ctx, 18f)
            )
        }

        root.addView(header)
        root.addView(sectionLabel)
        root.addView(scroll)
        root.addView(emptyView)
        root.addView(footer)
        return root
    }

    private fun buildEmptyView(): LinearLayout {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(Ui.dp(ctx, 36f), 0, Ui.dp(ctx, 36f), Ui.dp(ctx, 24f))
        }
        box.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo)
            imageTintList = ColorStateList.valueOf(Ui.color(ctx, R.color.accent))
            alpha = 0.9f
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 66f), Ui.dp(ctx, 66f))
        })
        box.addView(Ui.text(ctx, 19f, Ui.color(ctx, R.color.text), true, "还没有地址").apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 20f), 0, 0)
        })
        box.addView(
            Ui.text(
                ctx, 13f, Ui.color(ctx, R.color.textDim), false,
                "在电脑上运行 dsh web，把打印出的完整地址\n（带 ?token=…）粘贴到这里。"
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(Ui.dp(ctx, 3f).toFloat(), 1f)
                setPadding(0, Ui.dp(ctx, 10f), 0, 0)
            }
        )
        box.addView(Ui.primaryButton(ctx, "添加 DSH 地址").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 26f) }
            setOnClickListener { openEditor(null) }
        })
        return box
    }

    private fun reload() {
        items = store.load()
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val empty = items.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        scroll.visibility = if (empty) View.GONE else View.VISIBLE
        sectionLabel.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return

        listContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 4f)
            )
        })
        items.forEach { listContainer.addView(buildCard(it)) }
        listContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 8f)
            )
        })
    }

    private fun buildCard(instance: Instance): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.clickableCard(ctx, Ui.color(ctx, R.color.surface), 18f)
            elevation = Ui.dp(ctx, 2f).toFloat()
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 14f), Ui.dp(ctx, 12f), Ui.dp(ctx, 14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(Ui.dp(ctx, 18f), Ui.dp(ctx, 5f), Ui.dp(ctx, 18f), Ui.dp(ctx, 5f))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openTab(instance.id)
                showWorkspace()
            }
            setOnLongClickListener {
                showItemMenu(instance)
                true
            }
        }

        val avatar = Ui.text(
            ctx, 17f, Color.WHITE, true, Ui.initial(instance.name)
        ).apply {
            gravity = Gravity.CENTER
            background = Ui.circle(ctx, Ui.avatarColor(instance.name))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 42f), Ui.dp(ctx, 42f)).apply {
                rightMargin = Ui.dp(ctx, 14f)
            }
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(Ui.text(ctx, 16f, Ui.color(ctx, R.color.text), true, instance.name))
        val meta = buildString {
            append(UrlTools.displayTarget(instance.url))
            if (live.containsKey(instance.id)) {
                append(" · 已打开")
            } else if (instance.lastUsedAt > 0) {
                append(" · ")
                append(relativeTime(instance.lastUsedAt))
            }
        }
        column.addView(
            Ui.text(ctx, 12f, Ui.color(ctx, R.color.textDim), false, meta).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, Ui.dp(ctx, 5f), 0, 0)
            }
        )

        val chevron = Ui.text(ctx, 20f, Ui.color(ctx, R.color.textDim), false, "›").apply {
            setPadding(Ui.dp(ctx, 10f), 0, 0, 0)
        }

        card.addView(avatar)
        card.addView(column)
        card.addView(chevron)
        return card
    }

    private fun showItemMenu(instance: Instance) {
        ActionSheet(
            this,
            instance.name,
            listOf(
                ActionSheet.Action("打开") {
                    openTab(instance.id)
                    showWorkspace()
                },
                ActionSheet.Action("编辑地址") { openEditor(instance) },
                ActionSheet.Action("复制地址") {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DSH 地址", instance.url))
                    toast("地址已复制（含 token，注意别外传）")
                },
                ActionSheet.Action("删除", danger = true) { confirmDelete(instance) }
            )
        ).show()
    }

    private fun confirmDelete(instance: Instance) {
        AlertDialog.Builder(this)
            .setMessage("删除「${instance.name}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                closeTabIfOpen(instance.id)
                store.delete(instance.id)
                reload()
            }
            .show()
    }

    private fun openEditor(existing: Instance?, prefillUrl: String? = null) {
        EditorDialog(this, existing, prefillUrl) { name, url ->
            if (existing == null) {
                store.upsert(store.newInstance(name, url))
            } else {
                store.upsert(existing.copy(name = name, url = url))
            }
            syncOpenInstances()
            reload()
        }.show()
    }

    /** 编辑地址或名称后，把已打开的 WebView 同步到最新 URL。 */
    private fun syncOpenInstances() {
        val latest = store.load().associateBy { it.id }
        live.keys.forEach { id ->
            val fresh = latest[id] ?: return@forEach
            val old = instances[id]
            instances[id] = fresh
            if (old?.url != fresh.url) {
                live[id]?.loadUrl(fresh.url)
            }
        }
        renderTabs()
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val url = UrlTools.normalize(text) ?: return
        intent.removeExtra(Intent.EXTRA_TEXT)
        openEditor(null, url)
    }

    private fun relativeTime(timestamp: Long): String {
        val delta = System.currentTimeMillis() - timestamp
        return when {
            delta < 60_000 -> "刚刚"
            delta < 3_600_000 -> "${delta / 60_000} 分钟前"
            delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
            delta < 7 * 86_400_000L -> "${delta / 86_400_000} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        }
    }

    // ------------------------------------------------------------- 工作区

    private fun buildWorkspace(): LinearLayout {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(ctx, R.color.bg))
        }

        // 一行 header：返回 + 标签（可横滑）+ 打开新地址 + 更多。
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.color(ctx, R.color.surface))
            setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 54f)
            )
        }
        val back = Ui.roundIconButton(ctx, "‹", "返回列表").apply {
            textSize = 28f
            setOnClickListener { showList() }
        }
        tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 2f), 0)
        }
        tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val refresh = Ui.roundIconButton(ctx, "↻", "刷新").apply {
            textSize = 18f
            setOnClickListener { currentId?.let { live[it]?.reload() } }
        }
        val menu = Ui.roundIconButton(ctx, "⋮", "更多").apply {
            textSize = 20f
            setOnClickListener { showMenu(it) }
        }
        header.addView(back)
        header.addView(tabScroll)
        header.addView(refresh)
        header.addView(menu)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.color(ctx, R.color.accent))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 2f)
            )
            visibility = View.GONE
        }

        authBar = Ui.text(
            ctx, 13f, Ui.color(ctx, R.color.warnText), false,
            "登录已过期，点这里更新地址"
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(ctx, 14f), Ui.dp(ctx, 12f), Ui.dp(ctx, 14f), Ui.dp(ctx, 12f)
            )
            background = Ui.rounded(ctx, Ui.color(ctx, R.color.warnBg), 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), Ui.dp(ctx, 8f))
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { editUrlDialog() }
        }

        host = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(header)
        root.addView(progressBar)
        root.addView(View(ctx).apply {
            setBackgroundColor(Ui.color(ctx, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1f)
            )
        })
        root.addView(authBar)
        root.addView(host)
        return root
    }

    private fun showInstancePicker() {
        val all = store.load()
        if (all.isEmpty()) {
            toast("还没有地址")
            return
        }
        val actions = all.map { instance ->
            ActionSheet.Action(
                if (live.containsKey(instance.id)) "${instance.name}（已打开）" else instance.name
            ) {
                openTab(instance.id)
                showWorkspace()
            }
        } + ActionSheet.Action("管理地址") { showList() }
        ActionSheet(this, "打开地址", actions).show()
    }

    // --------------------------------------------------------------- 标签

    private fun openTab(id: String) {
        val instance = store.load().firstOrNull { it.id == id }
        if (instance == null) {
            if (live.isEmpty()) showList()
            return
        }
        instances[id] = instance
        if (!live.containsKey(id)) {
            if (live.size >= MAX_LIVE) evictOldest()
            val view = createWebView(instance)
            live[id] = view
            host.addView(view)
            view.loadUrl(instance.url)
        }
        selectTab(id)
        store.touch(id)
    }

    private fun selectTab(id: String) {
        currentId = id
        live.forEach { (key, view) ->
            view.visibility = if (key == id) View.VISIBLE else View.GONE
        }
        authBar.visibility = View.GONE
        val view = live[id]
        val progress = view?.progress ?: 0
        progressBar.progress = progress
        progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
        renderTabs()
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        live.forEach { (id, _) ->
            val instance = instances[id] ?: return@forEach
            tabBar.addView(buildTab(id, instance))
        }
        tabBar.addView(buildAddTab())
    }

    /** 加号跟在最后一个标签后面，随标签一起横向滚动（Chrome 手机版的做法）。 */
    private fun buildAddTab(): View {
        val ctx = this
        return Ui.text(ctx, 16f, Ui.color(ctx, R.color.accentInk), false, "＋").apply {
            gravity = Gravity.CENTER
            contentDescription = "打开另一个地址"
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                Ui.circle(ctx, Ui.color(ctx, R.color.accent)),
                Ui.circle(ctx, 0xFFFFFFFF.toInt())
            )
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 28f), Ui.dp(ctx, 28f)).apply {
                setMargins(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f), 0)
            }
            setOnClickListener { showInstancePicker() }
        }
    }

    private fun buildTab(id: String, instance: Instance): View {
        val ctx = this
        val selected = id == currentId
        val tab = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(ctx, 8f), Ui.dp(ctx, 5f),
                Ui.dp(ctx, 11f), Ui.dp(ctx, 5f)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(Ui.dp(ctx, 4f), Ui.dp(ctx, 2f), Ui.dp(ctx, 4f), Ui.dp(ctx, 2f)) }
            background = if (selected) {
                Ui.rounded(ctx, accentAlpha(38), 14f)
            } else {
                android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(Ui.color(ctx, R.color.ripple)),
                    null,
                    Ui.rounded(ctx, 0xFFFFFFFF.toInt(), 14f)
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(id) }
            setOnLongClickListener {
                confirmClose(id, instance.name)
                true
            }
        }
        val avatar = Ui.text(
            ctx, 10.5f, Color.WHITE, true, Ui.initial(instance.name)
        ).apply {
            gravity = Gravity.CENTER
            background = Ui.circle(ctx, Ui.avatarColor(instance.name))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 20f), Ui.dp(ctx, 20f)).apply {
                rightMargin = Ui.dp(ctx, 7f)
            }
        }
        val label = Ui.text(
            ctx, 12f,
            Ui.color(ctx, if (selected) R.color.accent else R.color.textDim),
            selected, instance.name
        ).apply {
            maxWidth = Ui.dp(ctx, 104f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tab.addView(avatar)
        tab.addView(label)
        return tab
    }

    private fun confirmClose(id: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage("关闭「$name」？页面状态会丢失。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ -> closeTab(id) }
            .show()
    }

    private fun closeTab(id: String) {
        val view = live.remove(id) ?: return
        host.removeView(view)
        view.stopLoading()
        view.destroy()
        instances.remove(id)
        if (id == currentId) {
            currentId = null
            val next = live.keys.lastOrNull()
            if (next == null) {
                showList()
                return
            }
            selectTab(next)
        } else {
            renderTabs()
        }
    }

    private fun closeTabIfOpen(id: String) {
        if (live.containsKey(id)) closeTab(id)
    }

    private fun evictOldest() {
        val victim = live.entries.firstOrNull { it.key != currentId } ?: return
        val name = instances[victim.key]?.name ?: "旧页面"
        live.remove(victim.key)
        host.removeView(victim.value)
        victim.value.stopLoading()
        victim.value.destroy()
        instances.remove(victim.key)
        toast("已释放「$name」以节省内存（最多同时保留 $MAX_LIVE 个）")
    }

    private fun accentAlpha(alpha: Int): Int {
        val base = Ui.color(this, R.color.accent)
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    // ------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(instance: Instance): WebView {
        val ctx = this
        val view = WebView(ctx).apply {
            tag = instance.id
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Ui.color(ctx, R.color.bg))
            webViewClient = pageClient
            webChromeClient = chromeClient
            setDownloadListener(DownloadListener { url, userAgent, disposition, mime, _ ->
                handleDownload(url, userAgent, disposition, mime)
            })
            addJavascriptInterface(Bridge(instance.id), "DshBridge")
        }
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        // DSH 的语音插件在 await getUserMedia 之后才创建 AudioContext，
        // 此时用户手势已经过期；这个开关对应 Chromium 的 no-user-gesture-required 策略。
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.textZoom = 100
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        return view
    }

    private val pageClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val instance = instanceOf(view) ?: return false
            val uri = request.url
            val scheme = uri.scheme.orEmpty().lowercase()
            if (scheme == "http" || scheme == "https") {
                if (UrlTools.sameOrigin(uri.toString(), instance.url)) return false
                openExternal(uri)
                return true
            }
            if (scheme == "intent") {
                return runCatching {
                    startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME))
                    true
                }.getOrDefault(true)
            }
            return runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }.getOrDefault(true)
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?
        ) {
            if (view.tag == currentId) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                authBar.visibility = View.GONE
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view.tag == currentId) progressBar.visibility = View.GONE
            if (url == null) return
            detectAuthFailure(view)
            injectPageHooks(view, url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            if (view.tag != currentId) return
            toast("加载失败：${error.description}")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: android.webkit.WebResourceResponse
        ) {
            if (request.isForMainFrame && errorResponse.statusCode == 401 &&
                view.tag == currentId
            ) {
                showAuthBar()
            }
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                runCatching {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(
                            KEY_CONSOLE,
                            "[${message.sourceId()}:${message.lineNumber()}] ${message.message()}"
                        )
                        .commit()
                }
            }
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view.tag != currentId) return
            progressBar.progress = newProgress
            if (newProgress >= 100) progressBar.visibility = View.GONE
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (!wantsAudio) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                return
            }
            pendingMicRequest?.deny()
            pendingMicRequest = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (e: Exception) {
                filePathCallback = null
                toast("无法打开文件选择器")
                false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val request = pendingMicRequest
        pendingMicRequest = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            request?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request?.deny()
            showMicPermissionHelp()
        }
    }

    private fun showMicPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("麦克风权限被拒绝")
            .setMessage("语音输入需要麦克风权限。可以在系统设置里手动开启。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val callback = filePathCallback ?: return
        filePathCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
    }

    private fun detectAuthFailure(view: WebView) {
        view.evaluateJavascript(
            "(document.body && document.body.innerText || '').slice(0, 300)"
        ) { raw ->
            val text = runCatching {
                org.json.JSONTokener(raw).nextValue() as? String ?: ""
            }.getOrDefault("")
            if (text.contains(AUTH_MARKER) && view.tag == currentId) showAuthBar()
        }
    }

    private fun showAuthBar() {
        authBar.visibility = View.VISIBLE
    }

    private fun injectPageHooks(view: WebView, url: String) {
        val instance = instanceOf(view) ?: return
        if (!UrlTools.sameOrigin(url, instance.url)) return
        view.evaluateJavascript(BLOB_HOOK, null)
        view.evaluateJavascript(NOTIFY_HOOK, null)
    }

    private fun openExternal(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { toast("没有可打开此链接的应用") }
    }

    // --------------------------------------------------------------- 菜单

    private fun showMenu(anchor: View) {
        val instance = currentInstance ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 6, 0, "在系统浏览器打开")
        popup.menu.add(0, 2, 2, "编辑地址")
        popup.menu.add(0, 3, 3, "复制地址")
        popup.menu.add(0, 5, 4, "查看页面错误")
        popup.menu.add(0, 7, 5, "发送测试通知")
        popup.menu.add(0, 8, 6, "通知诊断")
        popup.menu.add(0, 9, 7, "检查更新")
        val secure = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SECURE, false)
        popup.menu.add(0, 4, 5, if (secure) "关闭防截屏" else "开启防截屏")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                6 -> openExternal(Uri.parse(instance.url))
                2 -> editUrlDialog()
                3 -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DSH 地址", instance.url))
                    toast("地址已复制（含 token，注意别外传）")
                }
                4 -> toggleSecure(!secure)
                5 -> showConsoleError()
                7 -> {
                    currentId?.let { postNotification(it, "done", "这是一条测试通知") }
                    toast("已发送测试通知")
                }
                8 -> showNotifyDiagnostics()
                9 -> checkForUpdate()
            }
            true
        }
        popup.show()
    }

    // --------------------------------------------------------------- 更新

    private data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String)

    private fun checkForUpdate() {
        toast("正在检查更新…")
        Thread {
            val result = runCatching { fetchLatestRelease() }
            runOnUiThread {
                result.onSuccess { release ->
                    if (release == null) toast("已是最新版本")
                    else showUpdateDialog(release)
                }.onFailure {
                    toast("检查更新失败：${it.message}")
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val connection = URL(REPO_RELEASES_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "DS-Bot-Android")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = org.json.JSONObject(body)
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isBlank()) return null
        val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        if (compareVersions(tag, current) <= 0) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isNullOrBlank()) return null
        return ReleaseInfo(tag, json.optString("body"), apkUrl)
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val notes = release.notes.trim().ifBlank { "（该版本没有附更新说明）" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${release.version}")
            .setMessage(notes.take(2000))
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载并安装") { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("系统还没有允许 DS Bot 安装应用。请在设置里打开「安装未知应用」，然后回来重试。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .show()
            return
        }
        runCatching {
            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("DS Bot ${release.version}")
                .setDescription("正在下载更新")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    this, Environment.DIRECTORY_DOWNLOADS, "ds-bot-${release.version}.apk"
                )
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            updateDownloadId = manager.enqueue(request)
            registerDownloadReceiver()
            toast("开始下载更新…")
        }.onFailure { toast("无法开始下载：${it.message}") }
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != updateDownloadId) return
                unregisterDownloadReceiver()
                if (installDownloaded(id)) updateDownloadId = -1L
            }
        }
        downloadReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        downloadReceiver = null
    }

    private fun installDownloaded(id: Long): Boolean {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
        if (uri == null) {
            toast("更新包下载失败，请重试")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(intent)
            true
        }.getOrElse {
            toast("无法启动安装器：${it.message}")
            false
        }
    }

    private fun showNotifyDiagnostics() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val permission = if (Build.VERSION.SDK_INT < 33) {
            "系统版本低于 13，无需运行时权限"
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) "已授予" else "未授予（通知会被系统丢弃）"
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) "未创建" else "已创建"
        } else "不需要"
        val events = prefs.getString("notify_debug", null)
            ?: "（还没有收到页面的任何事件）"
        val text = buildString {
            append("通知权限：").append(permission).append("\n")
            append("通知通道：").append(channel).append("\n")
            append("App 在前台：").append(if (foreground) "是" else "否").append("\n")
            append("当前页面：").append(currentInstance?.name ?: "无").append("\n\n")
            append("最近事件：\n").append(events)
        }
        AlertDialog.Builder(this)
            .setTitle("通知诊断")
            .setMessage(text)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("通知诊断", text))
                toast("已复制")
            }
            .setNeutralButton("清空记录") { _, _ ->
                prefs.edit().remove("notify_debug").apply()
            }
            .show()
    }

    private fun showConsoleError() {
        val error = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_CONSOLE, null)
        if (error.isNullOrBlank()) {
            toast("没有记录到页面错误")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("最近的页面错误")
            .setMessage(error)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("页面错误", error))
                toast("已复制")
            }
            .show()
    }

    private fun toggleSecure(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SECURE, enabled).apply()
        toast(if (enabled) "已开启防截屏（最近任务里会隐藏内容）" else "已关闭防截屏")
    }

    private fun editUrlDialog() {
        val instance = currentInstance ?: return
        EditorDialog(this, instance, null) { name, url ->
            instance.name = name
            instance.url = url
            store.upsert(instance)
            instances[instance.id] = instance
            authBar.visibility = View.GONE
            live[instance.id]?.loadUrl(url)
            renderTabs()
            reload()
        }.show()
    }

    // --------------------------------------------------------------- 通知

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    private fun recordEvent(label: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val old = prefs.getString("notify_debug", null)?.lines()?.take(11) ?: emptyList()
        prefs.edit().putString("notify_debug", (listOf("$stamp $label") + old).joinToString("\n")).apply()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "DSH 任务提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "任务完成或需要你操作时提醒"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun postNotification(instanceId: String, kind: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val name = instances[instanceId]?.name ?: "DSH"
        val body = if (text.isBlank()) {
            if (kind == "question") "等待你的操作" else "任务已完成"
        } else text
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_ID, instanceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(this, instanceId.hashCode(), intent, flags)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(instanceId.hashCode(), notification)
    }

    // ------------------------------------------------------------ 下载/上传

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            mimeType?.let { request.setMimeType(it) }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .ifBlank { "dsh-download" }
            request.setTitle(name)
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("开始下载 $name")
        }.onFailure { toast("下载失败：${it.message}") }
    }

    /** 页面里的 blob: 下载 WebView 收不到 DownloadListener，走 JS 桥回传。 */
    inner class Bridge(private val instanceId: String) {

        @JavascriptInterface
        fun save(name: String, mime: String, base64: String) {
            Thread {
                val result = runCatching { saveBlob(name, mime, base64) }
                runOnUiThread {
                    result.onSuccess { toast("已保存到「下载」：$it") }
                        .onFailure { toast("保存失败：${it.message}") }
                }
            }.start()
        }

        @JavascriptInterface
        fun tooLarge(name: String, size: Long) {
            runOnUiThread {
                toast("「$name」太大（${size / 1024 / 1024} MB），请在电脑上下载")
            }
        }

        @JavascriptInterface
        fun failed(name: String, error: String) {
            runOnUiThread { toast("下载「$name」失败：$error") }
        }

        @JavascriptInterface
        fun notify(kind: String, text: String) {
            runOnUiThread {
                recordEvent("hook:$kind")
                if (foreground && inWorkspace && currentId == instanceId) {
                    recordEvent("skipped:visible")
                    return@runOnUiThread
                }
                postNotification(instanceId, kind, text)
                recordEvent("posted:$kind")
            }
        }

        @JavascriptInterface
        fun event(label: String) {
            runOnUiThread { recordEvent(label) }
        }
    }

    private fun saveBlob(name: String, mime: String, base64: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .ifBlank { "dsh-download" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建目标文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            safeName
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val file = File(dir, safeName)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }

    // ------------------------------------------------------------- 生命周期

    override fun onPause() {
        super.onPause()
        foreground = false
        CookieManager.getInstance().flush()
    }

    @Deprecated("framework Activity：手写返回逻辑")
    override fun onBackPressed() {
        if (inWorkspace) {
            val view = currentId?.let { live[it] }
            if (view != null && view.canGoBack()) view.goBack() else showList()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        unregisterDownloadReceiver()
        live.values.forEach { view ->
            view.stopLoading()
            view.destroy()
        }
        live.clear()
        super.onDestroy()
    }

    // --------------------------------------------------------------- 杂项

    private fun showCrashReportIfAny() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val log = prefs.getString(DshApp.KEY_CRASH, null) ?: return
        prefs.edit().remove(DshApp.KEY_CRASH).apply()
        AlertDialog.Builder(this)
            .setTitle("上次崩溃了")
            .setMessage(log.take(1600))
            .setPositiveButton("复制日志") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DSH 崩溃日志", log))
                toast("崩溃日志已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val NOTIFY_HOOK = """
        (function () {
          if (window.__dshNotifyHooked) return;
          window.__dshNotifyHooked = true;
          if (window.DshBridge && DshBridge.event) DshBridge.event('hooked');
          var lastBusy = false;
          var lastQuestion = null;
          function isBusy() {
            return document.querySelector(
              'button[aria-label="停止生成"]:not([disabled]),button[aria-label="Stop generating"]:not([disabled])'
            ) !== null;
          }
          function questionKey() {
            var q = document.querySelector('[data-question-key]');
            if (q) return 'q:' + (q.getAttribute('data-question-key') || '');
            var p = document.querySelector('[data-plan-review-key]');
            if (p) return 'p:' + (p.getAttribute('data-plan-review-key') || '');
            return null;
          }
          function tick() {
            try {
              var busy = isBusy();
              if (busy !== lastBusy && DshBridge.event) DshBridge.event('busy:' + (busy ? '1' : '0'));
              if (lastBusy && !busy) DshBridge.notify('done', '任务已完成');
              lastBusy = busy;
              var key = questionKey();
              if (key !== null && key !== lastQuestion) DshBridge.notify('question', '等待你的操作');
              lastQuestion = key;
            } catch (e) { }
          }
          new MutationObserver(tick).observe(document.body, {
            childList: true, subtree: true, attributes: true,
            attributeFilter: ['aria-label', 'data-question-key', 'data-plan-review-key']
          });
          setInterval(tick, 3000);
          tick();
        })();
    """.trimIndent()

    private val BLOB_HOOK = """
        (function () {
          if (window.__dshBlobHooked) return;
          window.__dshBlobHooked = true;
          var original = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            try {
              var href = this.href || '';
              if (href.indexOf('blob:') === 0) {
                var name = this.download || ('dsh-' + Date.now());
                fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                  if (b.size > 60 * 1024 * 1024) { DshBridge.tooLarge(name, b.size); return; }
                  var reader = new FileReader();
                  reader.onload = function () {
                    var text = String(reader.result || '');
                    var comma = text.indexOf(',');
                    DshBridge.save(name, b.type || 'application/octet-stream',
                      comma >= 0 ? text.substring(comma + 1) : '');
                  };
                  reader.onerror = function () { DshBridge.failed(name, '读取失败'); };
                  reader.readAsDataURL(b);
                }).catch(function (e) { DshBridge.failed(name, String(e)); });
                return;
              }
            } catch (e) { }
            return original.apply(this, arguments);
          };
        })();
    """.trimIndent()
}
