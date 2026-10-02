package top.bilitv.player

/**
 * 播放地址候选排序 —— **纯逻辑，无 Android 依赖，可单测**。
 *
 * 从 [BiliPlayer] 里抽出来的原因有两个：
 *  1. 它有单测价值（P2P 域名判据一堆 `contains` / `endsWith`，写错一条就会
 *     把一条好好的 CDN 排到垫底去，而**表现只是"起播慢一点"**，没人会来查）；
 *  2. 设置页的「跳过 P2P 加速节点」要复用它。
 */
object CdnOrder {

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    /**
     * 是不是 P2P 加速节点。
     *
     * B 站在 `baseUrl` 里经常塞这类地址：`xy171x43x247x145xy.mcdn.bilivideo.cn:8082`
     * （一段段数字堆起来的域名）、以及 `*.edge.*:4483` 这类非 443 端口的域名。
     * 它们是给自家客户端做 P2P 加速用的，在部分运营商 / 路由器 / 系统级广告拦截下
     * 直接连不通 —— 而 `backupUrl` 里的 `*.bilivideo.com` 是常规 CDN，稳得多。
     */
    fun isP2pNode(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("mcdn.bilivideo") ||
            host.contains(".edge.") ||
            host.endsWith(":8082") ||
            host.endsWith(":4483")
    }

    /**
     * 排好候选地址。
     *
     * @param skipP2p 「跳过 P2P 加速节点」（设置页的线路策略开关）。
     *
     * ★ **全部被滤掉时回落成不过滤**：有些视频的 `baseUrl` / `backupUrl` **只有** P2P 节点，
     * 真按开关滤干净就等于"没有地址可播"。宁可慢一点，也不能因为一个偏好开关
     * 把能播的视频变成不能播 —— 这个开关的语义是"优先不用"，不是"宁可不播"。
     */
    fun order(urls: List<String>, skipP2p: Boolean = false): List<String> {
        val clean = urls.filter { it.isNotBlank() }
        if (!skipP2p) {
            // sortedBy 是稳定排序，同级之间保持 B 站给的原始顺序
            return clean.sortedBy { if (isP2pNode(it)) 1 else 0 }
        }
        val kept = clean.filterNot { isP2pNode(it) }
        return kept.ifEmpty { clean }
    }
}
