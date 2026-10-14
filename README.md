# OWOrnate · Monet 主题源码

本仓库只包含控制中心莫奈取色的源码与运行件，用于单独发布主题模块。它不包含管理器 app、Frida 二进制、预制 keys 或 OPPO APK 素材。

## 目录

| 路径 | 内容 |
| --- | --- |
| `native-src/` | ControlTheme、ThemePalette、FaceSource、ControlChrome、ControlCorners、CornerGeometry、ThemeBridge 等源码 |
| `vendor/` | Material Color Utilities 的 HCT / 量化 / Score / 工具类（Apache-2.0） |
| `standalone/hook.js` | 在 Launcher 进程内载入 theme.jar 的注入脚本 |
| `standalone/monet-service.sh` | 主题专用 root supervisor（ensure / status / stop / restart-launcher） |
| `standalone/module-manage.sh` | 表端模块生命周期脚本（status / install / enable / disable / uninstall） |
| `build.ps1` | 构建 WatchTheme-helper-v6.jar |
| `PROTOCOL.md` | 莫奈主题通道协议 v1 |
| `licenses/` | 第三方组件声明 |
| `LICENSE` | MIT |

## 架构：HCT helper v6 与双模式闭合 mailbox

- helper 运行在 Launcher 进程内，用 HCT 从当前表盘派生配色，并按 `mode` / `seed` / `corners` 应用。
- 控制通道是 Launcher 私有目录里的闭合 mailbox：app 写一条固定格式 `request.json`，helper 用 `FileObserver` 响应并写 `ack.json` 回执；app 不提供任意 shell 或外部端口入口。
- 双模式：独立模式使用本包运行目录 `/data/local/tmp/watchworkshop-runtime`，莫奈文件由管理器 APK 内置资源在表端安装；共享模式要求既有 watchhide v9 + helper v6，识别后直接管理，不替换共享运行文件。
- 协议、会话、过期与错误码见 `PROTOCOL.md`。

注入脚本只处理控制中心外观，不涉及 NFC、WatchHide、QQ、蓝点或全局 View Hook。

## 构建

```powershell
pwsh -File .\build.ps1 -SdkRoot <AndroidSDK> -JdkHome <JDK>
```

产物为 `dist/WatchTheme-helper-v6.jar`。构建只用 JDK 与 Android SDK（platform 与 build-tools 中的 d8），不联网。

## 分发与部署

管理器 1.0.0 起，莫奈模块 `theme.jar`、`hook.js`、`monet-service.sh`、`module-manage.sh` 随签名 APK 内置（`assets/monet/`），首次用电脑运行一次 `00-Setup.cmd` 部署管理器后，安装与生命周期都在手表上完成，不需要电脑或联网下载。

本源码仓库不带 Frida 二进制、预制 keys 或 OPPO APK 素材，无法单独完成整机部署；完整部署使用管理器发布的 Windows 安装包。

## 状态

空白候选待测：空白 root 手表首次部署、表端模块生命周期与整机重启恢复为待实机验收。在线目录与下载尚未接入。

[OWOrnate 完整部署包](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.0-rc2) · [本模块 RC2 源码发布](https://github.com/AUTOlightup/watch2-monet/releases/tag/v1.0.0-rc2)
