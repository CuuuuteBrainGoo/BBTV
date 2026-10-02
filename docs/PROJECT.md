# BBTV 项目说明

BBTV 是面向 Android TV、大屏和遥控器操作的第三方 Bilibili 客户端，也是用于探索 AI 辅助软件开发的学习项目。当前安装包版本为 **1.3.5 / 10305**。

## 功能与使用

- 浏览推荐、分区、动态、影视、直播，搜索视频并查看详情。
- 播放视频，切换可用画质、倍速、分 P、分集及合集。
- 显示弹幕和字幕，调整样式、屏蔽规则与密度。字幕默认关闭；互动弹幕只显示内容。
- 保存设备本地观看记录，提供登录、收藏和账号相关入口。
- 调整界面皮肤、导航顺序、启动页面、启动焦点及时间显示。

安装包见 [1.3.5 下载页](https://github.com/CuuuuteBrainGoo/BBTV/releases/tag/v1.3.5)。精简 Release 适用于 ARM64；通用 Release 和 Debug 包包含 arm64-v8a、armeabi-v7a、x86_64。该批 Release 使用测试签名，校验文件随安装包提供。

### 遥控器操作

| 场景 | 操作 |
|---|---|
| 页面浏览 | 方向键移动焦点，OK 确认，返回键返回上一层 |
| 默认分区切换 | OK 切换或刷新，焦点留在标签；按下键进入视频卡片 |
| 视频播放 | 菜单键呼出控制栏，再按菜单键或返回键关闭 |
| 播放侧栏 | 默认上键打开推荐，下键打开分 P／播放列表；可在播放设置调整 |
| 关闭侧栏 | 返回键关闭侧栏，回到视频播放 |
| 字幕 | 播放设置总开关与可选 CC 按钮联动 |

开启“省内存模式”时，以 OK 确认切换；关闭后可随导航焦点自动切换页面。

## 技术与目录

| 部分 | 当前选型 |
|---|---|
| 语言／构建 | Kotlin 2.0.21、JDK 17、AGP 8.7.3、Gradle 8.11.1 |
| 界面 | Jetpack Compose、Material3、遥控器焦点组件 |
| 播放 | Media3 1.4.1、DASH／HLS |
| 网络／图片 | OkHttp 4.12、Coroutines 1.9、Coil 2.7 |
| Android | minSdk 21，compileSdk／targetSdk 35；应用 ID `top.bilitv` |

`app/src/main/java/top/bilitv/` 中：`data` 负责接口、账号、本地数据和设置；`player` 负责选流与播放适配；`ui` 负责页面、导航和控件；`util` 提供公共工具。`app/src/test/` 为 JVM 测试；`docs/assets/` 存放 README 图片。

Media3 固定使用 1.4.1：已有 HEVC 解析回归曾在升级版本中失败。调整播放器版本前，需要重新验证真实流解析及播放兼容性。

## 构建

配置 JDK 17 和 Android SDK 35，通过环境变量或未提交的 `local.properties` 指定 SDK 路径。在项目根目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat -PbbtvUniversal=true :app:assembleRelease
```

三条命令分别用于测试与 Debug、默认 ARM64 Release、通用 Release。构建输出位于 `app/build/outputs/apk/`。签名和网络环境需按本机配置；仓库内部分辅助脚本也使用本机工具路径，使用前应核对配置。

## 已知限制与验证范围

- 接口可能随上游变化；登录状态、账号权限和设备解码能力会影响可用内容与画质。
- 部分设置仍在补齐，推荐来源、个性化推荐和接口切换尚有后续工作。
- 不提供弹幕编写或发送功能，不提供人像防弹幕遮挡。
- 1.3.5 的验证基线为 300 个 JVM 测试及 8 组手机 Debug UI 检查；未覆盖完整 Release UI、全部电视机型或正式帧率测试。
- 非空收藏列表分页、部分直播切档／线路及播放面板即时互动弹幕开关仍有验收缺口。

文档记录当前能力及限制；安装包仅供学习参考和体验，使用说明见 [README](../README.md)。
