![BBTV 横向头图](docs/assets/bbtv-header.png)

# <img src="tools/icon-source/bbtv-icon-square.png" width="40" height="40" alt="BBTV 图标"> BBTV

> **这是一个 AI 使用练习作品。**
>
> 作者是一名零基础、零开发经验、目前只会写 `print("hello world")` 的编程小白，正在通过这个项目探索如何借助 AI 完成软件开发。
>
> 所有内容及安装包仅供参考、学习和体验。请在体验、参考后 **24 小时内删除安装包**。如有侵权，请私信联系作者，我会及时处理并删除相关内容。

[![正式版 v1.4.44](docs/assets/badge-version.svg)](https://github.com/CuuuuteBrainGoo/BBTV/releases/tag/v1.4.44)
[![下载 APK](docs/assets/badge-download.svg)](https://github.com/CuuuuteBrainGoo/BBTV/releases/tag/v1.4.44)
[![最后更新 2026-10-04](docs/assets/badge-updated.svg)](https://github.com/CuuuuteBrainGoo/BBTV/commits/main/)

一个面向 **Android TV、大屏与遥控器**的 Bilibili 客户端：浏览视频 → 选择分集 → 播放、切换画质与弹幕字幕。兼顾手机横屏触摸操作，主要适配正常横向宽屏与超宽屏。

## 下载正式版

当前正式版为 **1.4.44 / 10444**。从本次起按正式版本发布，旧版预发布记录保留。

| 安装包 | 大小 | 适用设备 |
|---|---|---|
| [ARM64 精简 Release](https://github.com/CuuuuteBrainGoo/BBTV/releases/download/v1.4.44/BBTV-1.4.44-release-arm64-small.apk) | 3.57 MiB | ARM64 电视、盒子及手机 |
| [通用 Release](https://github.com/CuuuuteBrainGoo/BBTV/releases/download/v1.4.44/BBTV-1.4.44-release-universal.apk) | 3.58 MiB | arm64-v8a、armeabi-v7a、x86_64 |
| [Debug 调试包](https://github.com/CuuuuteBrainGoo/BBTV/releases/download/v1.4.44/BBTV-1.4.44-debug-universal.apk) | 16.47 MiB | 开发与问题排查，日常体验优先使用 Release |

发布页附有 SHA256 校验文件。Release 已启用代码与资源压缩；沿用既有签名以支持覆盖升级，详细说明见[项目文档](docs/PROJECT.md)。

## 本次重点更新 · 1.4.44

相比此前公开的 1.3.5，重点更新如下：

- **卡片自动排满宽屏**：以常规 16∶9 电视约 4 列为基准，按实际可用空间自动增加列数；支持紧凑、标准、大、特大及手动列数，普通视频与影视海报可分别调整。
- **边看边调播放器**：控制栏即时设置与设置页联动；默认画质只用于进入视频，当前画质切换只影响本次播放。画质和倍速按钮直接显示当前值。
- **遥控器与触屏操作**：菜单键开关控制栏，上／下键打开可配置的推荐与选集侧栏；触屏支持双击播放暂停、水平滑动定位、长按临时 2 倍速、左侧亮度／右侧音量及中央提示条。
- **弹幕、字幕和主题**：字幕默认关闭，读取视频接口字幕并支持语言选择；弹幕可调透明度、字号、速度、占屏范围与屏蔽规则，互动弹幕只显示且限制密度。增加可换主题及触屏长按拖动排序。
- **列表与播放可靠性**：完善动态、搜索、收藏、UP 投稿、关注与影视分页；加载失败保留已有内容并可重试，离页和换片取消旧请求。支持分 P、合集／播放列表、上一项／下一项及循环。
- **接口与界面整理**：提供推荐来源、个性化及支持范围内的 App 接口选择和一次失败回退；完善视频角标与本机历史标题，补充主要页面和设置的中英文资源。移除内置截图分享、时钟和人像防挡等外围功能。

主要语言资源已有中英文版本，完整翻译和应用内界面语言切换仍在完善。不同账号权限、设备解码能力及上游接口变化会影响可用内容和画质。

## 项目文档

[项目说明](docs/PROJECT.md)：功能与遥控器／触屏操作、技术结构、构建方法、验证范围及已知限制。

## 参考与致谢

- `Fro***19` — 架构与接口实现思路（MIT）
- `ca***99` — 弹幕与播放器实现参考
- `kk***ny` — Flutter 路线的 TV 客户端
- `bil***ins` — 接口与签名文档

<sub>为避免给原作者和维护者添麻烦，此处不展示具体仓库名，仅以打码后的 ID 致谢。</sub>
