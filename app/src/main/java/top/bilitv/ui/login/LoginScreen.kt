package top.bilitv.ui.login

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.auth.RiskControl
import top.bilitv.data.auth.TvLoginPoll
import top.bilitv.data.auth.WebLogin
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

/**
 * 扫码登录页。
 *
 * ## 为什么电视端的登录必须是"扫码"
 *
 * 电视遥控器输入 11 位手机号 + 密码是折磨。所以 B 站自己也是这条路径：
 * **电视上显示二维码 → 手机 App 扫 → 手机上点确认 → 电视端拿到登录态**。
 * 三款参考客户端全都是这么做的（`docs/04` §2）。
 *
 * ## 界面要解决的唯一难题：让用户知道"现在进行到哪一步了"
 *
 * 这条流程对用户来说是**黑盒**：扫了之后手机上确认，电视这边什么反馈都没有，
 * 用户就会反复重扫、以为坏了。所以状态机要如实显示给用户：
 *
 * | 状态 | 界面上说的话 |
 * |---|---|
 * | 还没扫 | 用 B 站手机客户端扫这个码 |
 * | **已经扫到** | **已扫到，请在手机上点「确认登录」** ← 这条最容易被漏掉 |
 * | 码过期 | 二维码已过期，正在换一张新的… |
 * | 成功 | 直接返回上一页，不用用户再点一次 |
 *
 * 成功的处理是 `onLoggedIn()` 立即回调，**不弹"登录成功"对话框让用户按确定** ——
 * 用户已经在手机上点过一次确认了，电视这边再要他按一次是多余的。
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val vm: LoginViewModel = viewModel()
    val theme = AppTheme.current

    LaunchedEffect(Unit) { vm.start() }

    // 登录成功就自动退回去。
    // ★ 用 LaunchedEffect 而不是写在轮询里：轮询属于 VM，
    //   "导航"属于界面，VM 不该知道有"返回"这回事。
    LaunchedEffect(vm.state) {
        if (vm.state is LoginState.Success) {
            // 重新登录意味着拿到新鲜凭据 —— 之前的风控过闸记录可以清了
            RiskControl.clear()
            delay(260)          // 让"登录成功"这一帧先渲染出来，别一闪而过
            onLoggedIn()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.background)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "扫码登录",
                style = TextStyle(fontSize = AppType.Jumbo, fontWeight = FontWeight.Bold),
                color = theme.textPrimary,
            )

            Box(
                modifier = Modifier
                    .size(QR_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = vm.qrBitmap
                when {
                    vm.state is LoginState.Success -> Text(
                        text = "登录成功",
                        style = TextStyle(fontSize = AppType.H3, fontWeight = FontWeight.Bold),
                        color = Color(0xFF1A1A1A),
                    )

                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                    )

                    else -> CircularProgressIndicator()
                }
            }

            Text(
                text = vm.state.hint,
                style = TextStyle(fontSize = AppType.Body1, fontWeight = FontWeight.Medium),
                color = if (vm.state is LoginState.Scanned) theme.primary else theme.textSecondary,
                textAlign = TextAlign.Center,
            )

            // 失败态才给一个可操作的东西。其它状态重试没有意义（码还在轮询）。
            if (vm.state is LoginState.Failed) {
                BackChip(onBack = { vm.start() }, modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = "重新获取二维码",
                    style = TextStyle(fontSize = AppType.Meta),
                    color = theme.textTertiary,
                )
            }
        }

        BackChip(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(theme.screenPadding),
        )
    }
}

private val QR_SIZE = 280.dp

/** 登录界面的状态。`hint` 是直接显示给用户的那句话。 */
sealed interface LoginState {
    val hint: String

    data object Loading : LoginState {
        override val hint = "正在获取二维码…"
    }

    data object Waiting : LoginState {
        override val hint = "打开 B 站手机客户端，扫一扫这个二维码"
    }

    data object Scanned : LoginState {
        override val hint = "已扫到，请在手机上点「确认登录」"
    }

    data object Success : LoginState {
        override val hint = "登录成功"
    }

    data class Failed(override val hint: String) : LoginState
}

class LoginViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var state by mutableStateOf<LoginState>(LoginState.Loading)
        private set
    var qrBitmap by mutableStateOf<Bitmap?>(null)
        private set

    /** 防止重复启动（进页面 + 失败重试都会调 [start]）。 */
    private var running = false

    fun start() {
        if (running) return
        running = true
        viewModelScope.launch {
            state = LoginState.Loading
            qrBitmap = null

            /*
             * ★ 优先走**网页端**扫码（2026-09-29 改造）。
             *
             * 原因：我们所有业务接口都是 `api.bilibili.com` 的网页端接口。
             * 用网页端登录取的凭据"户口一致"，从根上少一层风控矛盾；
             * 而且网页端**独有 `refresh_token`**，能续期（TV 端不能）。
             *
             * 三家参考客户端（blbl / MyTVB / chinasoul.bt）都有网页端这条路径
             * —— 其中 MyTVB 干脆**只用网页端**。见 `docs/27`。
             *
             * 网页端失败才退到 TV 端（老路，保底）。
             */
            val usedWeb = runWebLogin()
            if (!usedWeb && state !is LoginState.Success) {
                AppLog.w("Login", "网页端登录未走通，回退到 TV 端扫码")
                runTvLogin()
            }
            if (state !is LoginState.Success) {
                state = LoginState.Failed("二维码多次过期，点下面的按钮重新获取")
            }
            running = false
        }
    }

    /**
     * 网页端扫码流程。返回 false 表示"这条路走不通"（该回退 TV 端）。
     *
     * 和 TV 端唯一的结构差别：申请码和轮询是两个接口、参数是 `qrcodeKey`。
     *
     * ⚠️ 这里是 suspend 函数、**不在 CoroutineScope 上** —— 所以用
     * `currentCoroutineContext().isActive` 判活，而不是裸写 `isActive`
     * （那个是 CoroutineScope 的扩展属性，在这个位置编译不过）。
     */
    private suspend fun runWebLogin(): Boolean {
        var issued = 0
        while (currentCoroutineContext().isActive && issued < MAX_QR && state !is LoginState.Success) {
            val session = graph.api.webQrSession()
            if (session == null) {
                AppLog.w("Login", "网页端拿不到二维码")
                return false
            }
            issued++
            qrBitmap = renderQr(session.url)
            state = LoginState.Waiting
            AppLog.i("Login", "网页端已申请二维码（第 $issued 张）")

            var expired = false
            while (currentCoroutineContext().isActive && !expired && state !is LoginState.Success) {
                delay(POLL_INTERVAL_MS)
                when (val r = graph.api.webPoll(session.qrcodeKey)) {
                    is WebLogin.Poll.Success -> state = LoginState.Success
                    WebLogin.Poll.Scanned -> state = LoginState.Scanned
                    WebLogin.Poll.Waiting -> if (state is LoginState.Loading) state = LoginState.Waiting
                    WebLogin.Poll.Expired -> {
                        expired = true
                        state = LoginState.Waiting
                    }
                    is WebLogin.Poll.Failed -> {
                        AppLog.w("Login", "网页端轮询失败 code=${r.code}")
                        return false
                    }
                }
            }
        }
        return true
    }

    /** TV 端扫码流程（保底）。逻辑与改造前一致。 */
    private suspend fun runTvLogin() {
        var issued = 0
        while (currentCoroutineContext().isActive && issued < MAX_QR && state !is LoginState.Success) {
            val session = graph.api.tvQrSession()
            if (session == null) {
                state = LoginState.Failed("拿不到二维码，检查一下网络")
                return
            }
            issued++
            qrBitmap = renderQr(session.url)
            state = LoginState.Waiting
            AppLog.i("Login", "TV 端已申请二维码（第 $issued 张）")

            var expired = false
            while (currentCoroutineContext().isActive && !expired && state !is LoginState.Success) {
                delay(POLL_INTERVAL_MS)
                when (val r = graph.api.tvPoll(session.authCode)) {
                    is TvLoginPoll.Success -> state = LoginState.Success
                    TvLoginPoll.Scanned -> state = LoginState.Scanned
                    TvLoginPoll.Waiting -> if (state is LoginState.Loading) state = LoginState.Waiting
                    TvLoginPoll.Expired -> {
                        expired = true
                        state = LoginState.Waiting
                    }
                    is TvLoginPoll.Failed -> {
                        state = LoginState.Failed("登录失败：${r.message}（code=${r.code}）")
                        return
                    }
                }
            }
        }
    }
}

/** 两张二维码之间隔多久问一次。2 秒是"用户点完确认最多等 2 秒"和"别把接口打爆"的折中。 */
private const val POLL_INTERVAL_MS = 2000L

/** 最多自动换几张码。 */
private const val MAX_QR = 3

/**
 * 把 URL 画成二维码位图。
 *
 * 用 zxing 的 `QRCodeWriter` 生成 BitMatrix，再手工填进 Bitmap ——
 * 不走 `BarcodeEncoder`（那在 `zxing-android-embedded` 里，要多引一个库）。
 * 我们的依赖里只有 `com.google.zxing:core`，够用。
 *
 * 纠错级别用 M：二维码贴在电视上，用户要隔一两米扫，
 * L 级别稍有点反光就扫不出来；H 级别码点太密，远距离反而更难对焦。
 */
private fun renderQr(text: String, sizePx: Int = 640): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
}.getOrElse {
    AppLog.e("Login", "二维码渲染失败", it)
    null
}
