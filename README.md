# DS Bot

一个 Android 上的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）Web UI 外壳。

它不重写 DSH，只是给手机补上浏览器外壳缺的那几件事：多标签、任务通知、文件上传下载、地址与登录态管理。

> 非官方项目，与 DeepSeek 官方无关。需要你自己有一台能访问的 DSH 实例。

## 功能

- **多标签工作区**：每个地址一个 WebView，切换标签不重新加载；列表与工作区在同一页面，返回列表不会销毁标签
- **地址管理**：保存多个带 `?token=` 的完整地址，URL 用 Android Keystore 的 AES/GCM 加密后落盘
- **单行顶部栏**：返回、标签（横滑）、打开新地址、刷新、更多
- **本地通知**：任务生成结束、出现 `ask_user` 提问或计划审批时提醒，点击直达对应标签
- **文件**：网页文件选择器（相册/文件/相机）、普通下载（带 Cookie）、`blob:` 下载保存到系统「下载」
- **登录态**：识别 DSH 的 401 页面并提示更新地址；cookie 由 WebView 持久化
- **分享进来**：从浏览器或聊天应用分享链接，直接填入添加对话框
- **其他**：防截屏（隐藏最近任务预览）、深色/浅色跟随系统、外链交给系统浏览器

## 构建

需要 JDK 17、Android SDK Platform 34、Build-Tools 34.0.0。

```bash
./gradlew assembleDebug     # 调试包，无需签名
./gradlew assembleRelease   # 发布包，需要下面的签名配置
```

发布签名从项目根目录的 `keystore.properties` 读取，该文件不入库：

```properties
storeFile=keystore/release.jks
storePassword=******
keyAlias=******
keyPassword=******
```

没有这个文件时 `assembleRelease` 产出未签名 APK，可以直接用 Android Studio 的「Generate Signed Bundle / APK」。

依赖仓库默认走阿里云镜像（`settings.gradle`），国内网络可直接构建；如不需要可改回 `google()` / `mavenCentral()`。

## 使用

1. 在电脑上运行 `dsh web`，复制它打印的完整 URL（带 `?token=`）
2. 手机打开 DS Bot → 右上角 ＋ → 粘贴 → 保存
3. 点地址进入工作区；再点另一个地址会新增标签，原页面保留
4. 地址必须能从手机访问：
   - 推荐 `https://你的域名/?token=…`（反向代理到 DSH）
   - 局域网 `http://192.168.x.x:43120/?token=…`（应用允许明文 HTTP）
   - `127.0.0.1` 是手机自己，不能用

DSH 重启后 launchToken 会变，但已种下的 cookie 在 30 天内仍然有效；cookie 失效时才需要「编辑地址」重新粘贴。

## 已知限制

- **同一 authority 只能有一个登录态**。DSH 的 cookie 按 `host:port` 绑定，两个同域地址会互相覆盖（不同域名互不影响）
- **同时最多保留 4 个 WebView**，超出时释放最久未用的一个，避免低端机 OOM
- **通知依赖 WebView 存活**。App 进程被系统冻结或回收后收不到；要做到杀进程也能收，需要服务端推送（DSH 插件 + ntfy/Telegram 之类）
- **不可中断的任务可能误报「已完成」**：DSH 在任务不可打断时不渲染停止按钮，状态机只能据此判断
- 通知靠注入脚本匹配 DSH 的 DOM 特征（`aria-label="停止生成"`、`data-question-key`、`data-plan-review-key`），DSH 前端大改后可能需要同步调整

## 隐私

- 地址里的 token 等同登录凭证，只保存在本机，用 Keystore 加密
- 应用不收集、不上报任何数据，没有第三方 SDK、没有广告
- 防截屏默认关闭，可在页面 ⋮ 菜单里开启

## 界面

启动器图标取自 DSH 官方 favicon（`dsh-web-frontend/dist/favicon.svg`）。如涉及商标问题，请替换 `app/src/main/res/drawable/ic_launcher_foreground.xml` 与 `ic_logo.xml`。

## License

[MIT](LICENSE)
