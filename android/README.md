# UwU 安卓版

本目录把当前仓库的网页打包为本地 Android WebView 应用。上游为
[wq70/xinOVO](https://github.com/wq70/xinOVO)，保留上游代码、作者与既有致谢；
本仓库增加安卓封装，不把原项目标为原创。

## 使用

- Android 8.0 及以上，需要较新的 Android System WebView／Chrome。
- App 名称：UwU；包名：`com.yaoji.uwu`；首版：0.1.0。
- 页面和八份入口依赖随 APK 安装，聊天、生图、TTS、网络字体和在线内容仍需联网。
- 浏览器与 App 的数据空间独立。迁移前在旧网页导出完整备份，再在 App 导入。
- 更新时使用相同包名、相同签名、更大的 `versionCode` 覆盖安装；不要先卸载。
- 卸载和清除 App 数据会删除本地聊天数据，操作前请导出备份。
- 上传使用系统文件选择器，不要求整个相册或存储权限；导出使用系统“另存为”，最多 512 MB，较大备份请分批导出。
- 需要录音／相机时才申请对应权限，拒绝后可以继续使用其他功能。
- 系统返回键先处理页面返回；桌面返回将 App 放到后台。
- 这是网页功能的安卓封装，不包含常驻后台服务、原生推送或关闭后的 AI 回复任务。
- 当前只允许 HTTPS 服务地址，HTTP 明文地址需要先换成 HTTPS。

## 构建

需要 Node.js 22+、JDK 17、Gradle 8.11.1、Android SDK 35 和 Build Tools 35。

```sh
npm ci --ignore-scripts
node scripts/prepare-android.mjs
npm run check
gradle -p android assembleRelease lintRelease
```

`prepare-android.mjs` 先构建上游网页，然后复制运行文件，并按
`vendor-lock.json` 下载、校验和打包入口依赖。原网页不受 APK 专用修改影响。
APK 不启用网页 Service Worker 更新缓存，更新需安装新版 APK。

产物位于 `android/app/build/outputs/apk/release/`。
未提供签名时生成的 `app-release-unsigned.apk` 不能直接安装。

## 私人签名与 GitHub Actions

第一版使用单独生成的私人 JKS 签名，不使用每次变化的临时 debug 密钥。
签名私钥与密码不进 Git，需保留交付时的签名备份以便后续覆盖更新。

本地签名通过环境变量传入：
`YUAN_KEYSTORE`（JKS 路径）、`YUAN_STORE_PASSWORD`、
`YUAN_KEY_PASSWORD` 和 `YUAN_KEY_ALIAS`（默认 `yuan`）。

Actions 页面可运行 **Build Android APK**。在仓库的 Actions secrets 添加
`YUAN_KEYSTORE_BASE64`（原 JKS 的 Base64）、`YUAN_STORE_PASSWORD` 和
`YUAN_KEY_PASSWORD` 后，工作流会自动输出与首版一致签名的 APK。
未配置时只提供明确标为 unsigned 的产物，不能直接安装。
不要在公开仓库、Issue、日志或聊天正文粘贴私钥和密码。

## 验证

- `npm run check`：上游结构与功能回归检查。
- `gradle -p android assembleRelease lintRelease`：原生编译和静态检查。
- `NODE_PATH=<包含 playwright 的目录> node scripts/test-android-browser.mjs`：
  打包后页面的无外网初始化、页面返回、分块文件导出、取消和 IndexedDB 重载。
  可用 `OVO_TEST_CHROMIUM` 指定浏览器可执行文件。
- 浏览器测试模拟原生消息端；系统文件选择器、麦克风、相机、键盘及实际 API
  仍需在 Android 设备上验证。默认不填写或调用用户的收费 API。
