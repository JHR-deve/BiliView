# BiliView

BiliView 是由 JHR-deve 独立开发的 Android 应用，可下载 B 站视频和合集、批量转换离线缓存，并将滚动弹幕直接烧录进 MP4。支持 Android 10 及以上，界面采用 Material 3，跟随系统浅色和深色主题。

## 界面与功能

下方截图来自正在开发的 BiliView 0.6.2，正式发布版本以 GitHub Releases 为准。

| 合集批量下载 | 本地缓存转换 | 下载记录 |
| --- | --- | --- |
| <img src="docs/screenshots/collection.png" width="230" alt="合集列表标出当前视频，用户可勾选视频加入队列"> | <img src="docs/screenshots/cache.png" width="230" alt="本地转换页支持扫描 B 站缓存、批量转换和嵌入弹幕"> | <img src="docs/screenshots/history.png" width="230" alt="下载记录中展开合集文件夹，显示中断任务和完成的视频"> |
| 从视频打开合集时会定位到当前视频，多选后逐条下载。 | 自动配对缓存的音视频轨，可选择批量转换和嵌入弹幕。 | 按视频或合集搜索，查看进度，重试下载，打开、分享或删除成品。 |

## 开始使用

1. **链接下载**。粘贴 B 站视频链接、BV/AV 号或 b23.tv 短链接，解析后选择画质。也可解析 UP 主空间的合集或系列链接，多选后加入队列。
2. **本地转换**。可扫描哔哩哔哩及概念版的离线缓存，也可选一个共享文件夹。BiliView 会自动配对同目录的音视频文件，批量转换会跳过已有成品。
3. **嵌入弹幕**。下载和转换前可勾选「将弹幕嵌入 MP4」，导出后可用系统播放器观看。本机默认字号为 80%，不透明度为 70%。也可扫码登录并启用「跟随 B 站弹幕设置」。
4. **管理成品**。MP4 保存在「下载 / BiliMP4」，合集成品各自归入子文件夹。处理期间请保持应用打开。失败、中断或取消的链接下载可在原记录中重试。

### Shizuku 授权

自动扫描和读取 B 站官方离线缓存，需要启动 Shizuku 并授权 BiliView。Android 11 及以上可在「本地转换 → Shizuku 指南」查看配对和授权步骤。使用共享缓存文件夹时，可直接通过系统文件选择器授权。 [Shizuku 使用手册](https://shizuku.rikka.app/zh-hans/guide/setup/)

## 构建

使用 Android SDK 36 和 JDK 17 或更高版本。设置 ANDROID_HOME，或通过 Android Studio 配置 SDK 后，即可在 PowerShell 中构建。

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

APK 位于 app/build/outputs/apk/debug/app-debug.apk。

## 软件更新

「关于」页显示当前版本、构建编号和本版更新内容。默认从浏览器打开 GitHub Releases。已获仓库权限的用户可登录 GitHub 下载 APK。

如需在应用内比较版本，构建时用 `-Pbilimp4UpdateFeed=https://你的更新域名/update.json` 配置一个可直接读取的 HTTPS JSON 地址。更新源返回以下字段。应用比较 `versionCode`，并在设备满足 `minSdk` 时提供更新说明和下载页入口。

~~~json
{
  "versionCode": 10,
  "versionName": "0.6.2",
  "minSdk": 29,
  "notes": "应用更名为 BiliView",
  "releaseUrl": "https://github.com/JHR-deve/BiliMP4-Android/releases/tag/v0.6.2"
}
~~~

发布时递增 `app/build.gradle` 的 `versionCode`，同步更新本版说明，使用与已安装版本一致的签名证书构建 APK。先把签名 APK 上传到对应 Release，再更新 JSON。APK 由用户下载后通过系统安装器覆盖安装。设备上的调试包使用原调试证书才能覆盖更新。

## 开源说明

项目采用 GPL-3.0，源码存放在独立的 GitHub 私有仓库。 [许可证](LICENSE) · [项目仓库](https://github.com/JHR-deve/BiliMP4-Android)

### 技术参考与依赖

Android 界面、缓存转换和弹幕烧录流程为本项目独立实现。B 站下载请求和 WBI 签名参考 Mimi Downloader。 [技术参考](https://github.com/stevenjoezhang/bilibili-downloader)

主要开源组件包括 AndroidX、Material Components、Media3、Shizuku API、ZXing 和 Protocol Buffers。各组件保留自己的许可证。
