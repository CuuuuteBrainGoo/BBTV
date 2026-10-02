package top.bilitv.data.auth

import org.json.JSONObject

/**
 * 风控过闸（Gaia VGate）检测。
 *
 * ## 背景：MyTVB 是怎么处理这件事的（2026-09-29 逆向 MyTVB v2.0.6）
 *
 * B 站的雅典娜风控（代号 `gaia`）在认为请求可疑时，会在**正常响应里夹带一个令牌**：
 *
 * ```json
 * { "code": 0, "data": { "v_voucher": "xxxxx" } }
 * ```
 *
 * 这个 `v_voucher` 的含义是：**"我怀疑你，但先给你个机会自证"**。
 * 拿它去 `/x/gaia-vgate/v1/register` 注册，会得到一道**极验滑块题**，
 * 拖完了换一个 `vtoken`，之后请求带上 `x-bili-gaia-vtoken` 头就能过。
 *
 * MyTVB 为此专门做了一个 `GaiaVgateActivity` + WebView + 极验 JS 桥。
 *
 * ## ⛔ 我们不做滑块，这是有意的设计决策
 *
 * **电视遥控器没法拖滑块。** 想想用户在沙发上拿着遥控器面对一道
 * "按住 → 拖动 → 对齐缺口"的题 —— 这是交互灾难，不是功能。
 *
 * 而且反过来看：**游客态从来不会触发 vgate**（风控对游客宽松）。
 * 所以遇到 vgate 时正确的做法不是"让用户拖滑块"，而是**回到一个不会触发它的状态**。
 *
 * ## 我们的做法：检测 → 提示 → 引导重新登录
 *
 * | 步骤 | 做什么 |
 * |---|---|
 * | 1 | 检测响应里有没有 `v_voucher` |
 * | 2 | 有 → 记录状态（不弹窗，不打断播放） |
 * | 3 | 界面层据此提示"B 站要求重新验证，请重新扫码登录" |
 *
 * 重新登录会拿到**新鲜的凭据 + 重新走一遍风控维护**（buvid 激活、ticket），
 * 绝大多数情况下这样就能恢复正常 —— 而不需要用户面对滑块。
 *
 * ## 为什么单独成一个文件
 *
 * "从 JSON 里判断要不要过闸"是个**纯函数判断**，可以单测；
 * 而它要影响的（提示什么、退到哪一页）属于界面层。
 * 混在一起的话，改文案要动解析、改解析要动文案。
 */
object RiskControl {

    /** 在响应体里见过的风控令牌。存下来是为了给日志/诊断用（**值不进日志**）。 */
    @Volatile
    var lastVVoucherSeen: Boolean = false
        private set

    @Volatile
    var lastSeenAtMs: Long = 0L
        private set

    /**
     * 检查响应体里有没有风控令牌。**纯函数，可单测。**
     *
     * ## 它可能出现的位置（三个都查）
     *
     * 1. `data.v_voucher` —— 最常见
     * 2. `data.gaia_vtoken` —— 已经过闸的标记（这是好事，说明被放行了）
     * 3. `data.voucher` —— 另一种写法（少数接口用）
     *
     * ## 为什么三个都要查而不是只查 `v_voucher`
     *
     * 因为**它们出现的层级会变**。B 站不同接口对同一个东西的包装不一样，
     * 有的放在 `data` 下，有的放在 `result` 下。只查一个位置的话，
     * 换个接口就漏检 —— 而漏检的表现是"播放莫名其妙失败但你不知道为什么"。
     *
     * @param raw 接口返回的原始 JSON 文本。**不是 JSON 时返回 null**（不抛异常）。
     */
    fun detect(raw: String): Verdict? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        // 顶层和 data 两层都找一遍
        val scopes = listOfNotNull(
            json,
            json.optJSONObject("data"),
            json.optJSONObject("result"),
        )

        for (scope in scopes) {
            if (scope.optString("v_voucher").isNotBlank()) return Verdict.NEED_VERIFY
            if (scope.optString("voucher").isNotBlank()) return Verdict.NEED_VERIFY
            if (scope.optString("gaia_vtoken").isNotBlank()) return Verdict.VERIFIED
        }
        return null
    }

    /** 检测 + 记录状态。业务层用这个（而不是只调 [detect]）。 */
    fun checkAndRecord(raw: String): Verdict? {
        val v = detect(raw) ?: return null
        if (v == Verdict.NEED_VERIFY) {
            lastVVoucherSeen = true
            lastSeenAtMs = System.currentTimeMillis()
        }
        return v
    }

    /**
     * 刚才是不是被要求过闸。
     *
     * 界面层用它决定要不要显示"重新登录"提示。
     * `withinMs` 默认 5 分钟 —— 太久之前的记录不该继续烦用户。
     */
    fun wasChallengedRecently(withinMs: Long = 5 * 60 * 1000L): Boolean =
        lastVVoucherSeen && (System.currentTimeMillis() - lastSeenAtMs) < withinMs

    /** 用户重新登录成功后调，把它清掉。 */
    fun clear() {
        lastVVoucherSeen = false
        lastSeenAtMs = 0L
    }

    enum class Verdict {
        /** 服务端要求过闸（我们有令牌但没做滑块） */
        NEED_VERIFY,

        /** 服务端已经放行（带了 gaia_vtoken） */
        VERIFIED,
    }
}
