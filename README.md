# B站 MP4

Android 10 及以上可用。界面使用 Google Material 3 组件，跟随系统主题，并在支持的设备上使用动态取色。

B站 MP4 是由 JHR-deve 开发的独立 Android 应用。通过侧边栏可进入链接下载、本地转换、弹幕设置和关于页面。

- **链接下载**支持普通投稿的 BV/AV 号、视频页链接、带分集号的链接和 b23.tv 短链接。先解析链接，再从 B 站返回的画质中选择并下载 MP4。
- **账号**支持用哔哩哔哩 App 扫码登录。可保存二维码，用同一部手机从扫一扫的相册入口选取。登录后可查看头像、昵称、UID、等级和大会员状态，会话在本机加密保存。
- **弹幕**可在下载时直接烧录进 MP4，用系统播放器或其他视频播放器即可观看。「弹幕设置」支持同步 B 站账号返回的弹幕偏好，也可调整本机字号、不透明度和颜色。
- **本地转换**可通过已运行并授权的 Shizuku 扫描哔哩哔哩和概念版的离线缓存。Android 11 及以上的系统文件选择器无法打开官方缓存所在的 `Android/data`，应用内的「Shizuku 指南」提供启动和授权步骤。
- 本地转换也可勾选「将弹幕嵌入 MP4」。应用优先读取缓存中的弹幕文件，缺少弹幕时可按分集在线补取，并与链接下载共用弹幕样式设置。
- 也可选择一个共享缓存文件夹。应用记住文件夹，并自动配对其中同目录的 video.m4s 和 audio.m4s，或分开的 MP4 和 M4A。
- 成品位于「下载 / BiliMP4」。处理时请保持应用打开。

## 权限与弹幕设置

扫描和读取 B 站官方离线缓存需要 Shizuku 正在运行，且用户已授权「B站 MP4」。链接下载使用 Android 网络权限，导入共享文件夹时由用户在系统文件选择器中授权。 [Shizuku 使用手册](https://shizuku.rikka.app/zh-hans/guide/setup/)

本机弹幕默认字号为 80%，不透明度为 70%。启用「跟随 B 站弹幕设置」时，账号返回的设置优先，缺项使用本机值。

## 构建

使用 Android SDK 36 和 JDK 17 或更高版本。设置 ANDROID_HOME，或通过 Android Studio 配置 SDK 后，即可在 PowerShell 中构建。

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

APK 位于 app/build/outputs/apk/debug/app-debug.apk。

## 源码与许可

项目采用 GPL-3.0，源码存放在独立的 GitHub 私有仓库。 [许可证](LICENSE) · [项目仓库](https://github.com/JHR-deve/BiliMP4-Android)

### 技术参考与依赖

Android 界面、缓存转换和弹幕烧录流程为本项目独立实现。B 站下载请求和 WBI 签名参考 Mimi Downloader。 [技术参考](https://github.com/stevenjoezhang/bilibili-downloader)

主要开源组件包括 AndroidX、Material Components、Media3、Shizuku API、ZXing 和 Protocol Buffers。各组件保留自己的许可证。
