package top.bilitv.player

/**
 * 「崩了能救命」的容错档位 —— **纯逻辑，无 Android 依赖，可单测**。
 *
 * ## 为什么需要这一组
 *
 * 面向电视的第三方客户端**没法要求用户去更新**：出了问题用户只能卸载重装，
 * 而重装之后还是会遇到同一个问题。所以"多留一条退路"比"多一个功能"值钱 ——
 * 这是少爷原话的意思（"稳定最重要，多设计一道可以多一点稳定"）。
 *
 * 三家参考客户端都有这一组开关（`docs/27` 的清单）：
 *  - MyTVB：`decoder_error_prefer_avc` / `network_error_prefer_avc` / `disable_host_backup`
 *  - chinasoul.bt：`custom_cdn` / `codec_auto` / `avc_compat` / `use_hardware_decode`
 *
 * ## 为什么单独一个文件而不是散在设置页里
 *
 * 这几项的共同点是**改一处就影响整条播放链路**，而且出错的表现都很安静：
 *  - CDN 偏好词写错 → 匹配不上任何域名 → 排序跟没写一样（界面照样"已设置"）
 *  - 软解开关判断写反 → 用户按了反而更容易崩
 *
 * 把它们抽成纯函数，才能在 JVM 单测里把边界钉死。
 */
object PlayTolerance {

    /**
     * 用户指定的 CDN 关键词。**空 = 不干预**。
     *
     * 语义是"**优先**"，不是"只用" —— 匹配到的排到最前，其余照原顺序跟在后面。
     * 理由同 [CdnOrder.order] 里那条：一个偏好开关不该把能播的视频变成不能播。
     * 用户填错了词（比如把 `upos` 写成 `upso`），结果只是排序没变，画面照常出。
     */
    fun orderByPreference(urls: List<String>, keyword: String): List<String> {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) return urls
        val (hit, miss) = urls.filter { it.isNotBlank() }.partition {
            CdnOrder.hostOf(it).lowercase().contains(k)
        }
        // 一个都没匹配上 → 原样返回，而不是返回空表
        return if (hit.isEmpty()) urls else hit + miss
    }
}
