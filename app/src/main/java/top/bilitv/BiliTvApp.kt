package top.bilitv

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import top.bilitv.data.api.BiliApi
import top.bilitv.data.auth.AuthBus
import top.bilitv.data.history.HistoryStore
import top.bilitv.data.settings.SettingsStore
import top.bilitv.data.sponsor.SponsorBlockApi
import top.bilitv.util.AppLog

/**
 * 应用级依赖容器。
 *
 * 没有引入 Koin / Hilt —— 本项目只有几个长生命周期对象（接口、设置、广告片段查询、观看记录），
 * 用 `by lazy` 手写比接一套 DI 框架更短、更好懂（少爷非科班，代码越少越好接手）。
 * 真要膨胀到十几个依赖再考虑框架。
 */
class BiliTvApp : Application(), ImageLoaderFactory {

    private var languageResources: Resources? = null
    override fun getResources(): Resources = languageResources ?: super.getResources()

    /** Use a separate resource configuration, preserving the device's locale and Activity identity. */
    internal fun refreshLanguage() {
        val locale = settings.interfaceLanguage.locale
        languageResources = locale?.let {
            val config = Configuration(baseContext.resources.configuration)
            config.setLocale(it)
            baseContext.createConfigurationContext(config).resources
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    override fun onCreate() {
        super.onCreate()
        refreshLanguage()
        // 尽早初始化：进程一挂，日志就是唯一的现场
        AppLog.init(this)
        /*
         * ★ 冷启动分段计时（少爷 2026-09-30 第 6 条）。
         *
         * 背景：电视实测冷启动 **4.76~4.97 秒**（目标 <2s，BT 是 2.4s），
         * logcat 里抓到 `Choreographer: Skipped 126 frames!` —— 主线程被一段
         * 同步工作卡了约 **2.1 秒**。但"卡了 2.1 秒"只说明现象，**不知道是哪一段**。
         *
         * 这几条 `Boot` 日志就是为了把 4.8 秒切成段：拿它们的时间戳去对
         * logcat 的 `Start proc` 与 `Displayed`，一眼看出时间花在
         * 「进程起来 → Application.onCreate」还是「Activity.onCreate → 第一帧」。
         *
         * ⚠️ 别删这几行 —— 它们是**唯一**能把"慢"定位到段的手段；
         * 每行只在启动时打一次，代价可以忽略。
         */
        AppLog.i("Boot", "①Application.onCreate 起点")
        /*
         * 认证总线：给风控维护层（WebCookieMaintainer）发请求用。
         * 必须在任何 BiliApi 被取用之前初始化 —— 因为 BiliApi 构造时会读它的 cookie。
         * 这里用 try/catch 包一层：初始化失败最坏是"没有风控加成"（回到改造前的行为），
         * 不该让 App 起不来。
         */
        runCatching { AuthBus.init(this) }
            .onFailure { AppLog.w("App", "AuthBus 初始化失败：${it.javaClass.simpleName}") }
        AppLog.i("Boot", "②Application.onCreate 完成（AppLog + AuthBus 就绪）")
    }

    /** B 站接口（带 CookieJar，登录态在这里） */
    val api: BiliApi by lazy { BiliApi(this) }

    /**
     * 广告片段查询。
     *
     * ★ 用**独立的** OkHttpClient，绝不复用 [api] 的 client ——
     * 那会把 B 站 Cookie 一起发给第三方服务器（`docs/03` §3.2 第 4 条硬要求）。
     */
    val sponsor: SponsorBlockApi by lazy { SponsorBlockApi(OkHttpClient()) }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    /**
     * 本机观看记录（含续播位置）。
     *
     * 放在应用级而不是播放页的 ViewModel 里：**播放页一退出 VM 就销毁了**，
     * 而续播位置必须在下次进来时还在；而历史页又是另一个页面。
     * 两边要看到同一份数据，它就只能住在应用级。
     *
     * ★ 它和 [api] 的云端历史**是两回事**，不要互相替代：
     * 云端那份要登录 + 有网络延迟，续播不能建在它上面（见 `HistoryEntry` 的说明）。
     */
    val history: HistoryStore by lazy { HistoryStore(this) }

    /**
     * Coil 全局配置。
     *
     * 低内存电视的生命线（`docs/05` §4 第 4 条）：显式限制图片内存缓存 64MB、磁盘 200MB，
     * 并且**不做原图解码**（每处 AsyncImage 都会带上实际显示尺寸）。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(64 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .crossfade(180)
        .build()
}
