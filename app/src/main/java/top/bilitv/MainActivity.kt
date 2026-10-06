package top.bilitv

import android.os.Bundle
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.view.WindowCompat
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import top.bilitv.ui.BiliTvRoot
import top.bilitv.ui.NavTab
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.BiliTvTheme
import top.bilitv.util.AppLog

/**
 * 唯一的 Activity。
 *
 * ## 皮肤状态为什么住在这一层
 *
 * [BiliTvTheme] 在这一层，它一变整棵树重组 —— 这就是"换肤即时生效"的全部机制。
 * 所以皮肤状态必须放在 `BiliTvTheme` **外面**，也就是这一层。
 * 放进 [BiliTvRoot] 里面的话，主题已经组合完了，改了也不会重绘。
 *
 * 初始值从设置里读，落盘也在这里做：设置页只负责说"用户想换成哪个"，
 * "存下来"和"让界面变"是两件事，一起放在状态的拥有者这里最不容易漏。
 *
 * ## 侧栏配置为什么也住在这一层
 *
 * 同一个理由：侧栏在外壳最外层，设置页改了它必须**立刻重排**。
 * 如果让 `AppShell` 自己去读设置，"改了没生效、要切一次页才变"就是必然结果。
 */
class MainActivity : ComponentActivity() {
    private var languageResources: Resources? = null
    override fun getResources(): Resources = languageResources ?: super.getResources()

    private fun refreshLanguage(app: BiliTvApp) {
        app.refreshLanguage()
        val locale = app.settings.interfaceLanguage.locale
        languageResources = locale?.let {
            val config = Configuration(baseContext.resources.configuration)
            config.setLocale(it)
            baseContext.createConfigurationContext(config).resources
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage(application as BiliTvApp)
    }
    /** 播放页触屏清焦点后，仍接住未被子视图处理的遥控器按键。离页即移除。 */
    internal var playerUnhandledKey: ((android.view.KeyEvent) -> Boolean)? = null
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        playerUnhandledKey?.invoke(event) == true || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        playerUnhandledKey?.invoke(event) == true || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 冷启动分段计时之二，见 BiliTvApp.onCreate 的说明
        AppLog.i("Boot", "③MainActivity.onCreate 起点")
        val app = application as BiliTvApp
        refreshLanguage(app)

        setContent {
            var language by remember { mutableStateOf(app.settings.interfaceLanguage) }
            val deviceConfiguration = LocalConfiguration.current
            val languageConfiguration = remember(deviceConfiguration, language) {
                Configuration(deviceConfiguration).apply { language.locale?.let { setLocale(it) } }
            }
            var skin by remember { mutableStateOf(app.settings.themeSkin) }
            // 从盘上读回用户配过的侧栏，经 NavTab.parse 兜底（认不出的名字丢掉、固定项补回来）
            var railTabs by remember { mutableStateOf(NavTab.parse(app.settings.navTabs)) }
            var cards by remember { mutableStateOf(Triple(app.settings.cardColumns, app.settings.collectionColumns, app.settings.cardSize)) }
            var uiOptions by remember { mutableStateOf(app.settings.interfaceAnimations to app.settings.showScrollbars) }
            var fontScale by remember { mutableStateOf(app.settings.interfaceFontScale) }
            var backdrop by remember { mutableStateOf(app.settings.backgroundStyle) }
            DisposableEffect(app.settings) {
                val stop = app.settings.observeChanges {
                    if (language != app.settings.interfaceLanguage) {
                        language = app.settings.interfaceLanguage
                        refreshLanguage(app)
                    }
                    cards = Triple(app.settings.cardColumns, app.settings.collectionColumns, app.settings.cardSize)
                    fontScale = app.settings.interfaceFontScale
                    uiOptions = app.settings.interfaceAnimations to app.settings.showScrollbars
                    backdrop = app.settings.backgroundStyle
                    skin = app.settings.themeSkin
                    railTabs = NavTab.parse(app.settings.navTabs)
                }
                onDispose { stop() }
            }

            // 冷启动分段计时之四：这一行跑起来 = 首帧的组合已经完成
            LaunchedEffect(Unit) { AppLog.i("Boot", "④首帧组合完成") }

            // Only resource reads recompose. Navigation, focus and player state stay in this Activity.
            CompositionLocalProvider(LocalConfiguration provides languageConfiguration) {
            BiliTvTheme(skin, cards.first, cards.second, cards.third, fontScale, uiOptions.first, uiOptions.second, backdrop) {
                Surface(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    color = AppTheme.current.background,
                ) {
                    BiliTvRoot(
                        onSkinChange = {
                            app.settings.themeSkin = it
                            skin = it
                        },
                        railTabs = railTabs,
                        onRailTabsChange = { names ->
                            app.settings.navTabs = names
                            railTabs = NavTab.parse(names)
                        },
                    )
                }
            }
            }
        }
    }
}
