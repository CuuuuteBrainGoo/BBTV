plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "top.bilitv"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.bilitv"
        minSdk = 21
        targetSdk = 35
        versionCode = 10305
        versionName = "1.3.5"

        // 目标机型是 ARM64 电视盒子；只打这一个 ABI，减体积减内存（docs/05 §4 第 8 条）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            /*
             * 电脑上的安卓模拟器是 x86_64 架构，不带这个 ABI 装上去会缺原生库。
             *
             * 两个都写上（不依赖 defaultConfig 的合并行为）：
             * 万一 AGP 在这里是"替换"而不是"追加"，加上 arm64 也不会丢东西。
             *
             * 只给 debug 加 —— release 是给电视的包，不该多背几 MB。
             * 我们 APK 里的原生库只有一个 Compose 的 libandroidx.graphics.path.so，
             * 它是可选加速库，两个架构都有对应版本。
             */
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 默认仍为最小 ARM64；实机测试通用包同时覆盖 32 位 Android 和模拟器。
            if (providers.gradleProperty("bbtvUniversal").orNull == "true") {
                ndk { abiFilters += listOf("armeabi-v7a", "x86_64") }
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += "x86_64" }
            matchingFallbacks += "release"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // 让 JVM 单测里的 android.jar stub 返回默认值而不是抛异常
        unitTests.isReturnDefaultValues = true
        // 把 println 打到控制台 —— Mp4ParserRegressionTest 的结果全靠打印看
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    /*
     * ⛔ Media3 固定在 1.4.1，**不要升到 1.5.x**。
     *
     * ## 为什么（已离线复现，不是猜的）
     * 1.5.0 为「L-HEVC 立体视频」在 `HevcConfig.parseImpl` 加了一段检测：
     *
     * ```java
     * if (seiData != null && currentVpsData != null) {
     *   stereoMode = (seiData.leftViewId == currentVpsData.layerInfos.get(0).viewId)
     *                                             ^^^^^^^^^^^^^^^^^^^^^ 空表 .get(0)
     * ```
     *
     * B 站 HEVC 流的 `hvcC` 三条件齐备 → Guava `checkElementIndex(0, 0)` 越界。
     * 致命之处：该方法的兜底 catch **只接 `ArrayIndexOutOfBoundsException`**，
     * 而 `IndexOutOfBoundsException` 是它的**兄弟类不是子类**，于是异常溜出解析器，
     * 被 `Loader` 包成 `UnexpectedLoaderException`，最终显示成
     * `ERROR_CODE_IO_UNSPECIFIED` —— 一个**看起来像网络问题**的错误码。
     *
     * 真机表现：HEVC 视频点播即黑屏。AVC / AV1 不受影响（走各自的解析路径）。
     *
     * ## 上游修了吗
     * 1.4.1 没有这行（是 1.5.0 新加的）。1.5.0 / 1.5.1 / 1.8.0 / 1.9.0 /
     * **1.11.1（当前最新）全都有，至今未修**。所以「升级」不是出路，只能留在 1.4.1。
     *
     * ## 怎么验证的
     * `tools/probe_mp4.py --full` 抓下真实流 → `Mp4ParserRegressionTest.parseCapturedCodecConfig`
     * 把 `hvcC` 直接喂给 `HevcConfig.parse()`，两个版本对照：
     *
     * | 版本   | HEVC 流的 hvcC                                   |
     * |--------|--------------------------------------------------|
     * | 1.5.1  | 崩在 `HevcConfig.parseImpl(HevcConfig.java:160)`  |
     * | 1.4.1  | `nalUnitLength=4 852x480 codecs=hvc1.1.6.L120.90` |
     *
     * ## 要升级时
     * 先跑 `tools/probe_mp4.py <bvid> <cid> --save ./tmp/samples --full` 抓几个流，
     * 再用上面那个测试确认新版本不再崩，然后才动这里。
     * 详见 `docs/08-播放黑屏诊断与修复.md` §8。
     */
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    /**
     * 用 OkHttp 换掉 ExoPlayer 自带的数据源。
     *
     * 自带的 `DefaultHttpDataSource` 基于 `HttpURLConnection`，**不做 IPv4/IPv6 双栈竞速**：
     * 当 CDN 域名有 AAAA 记录而手机所在网络 IPv6 实际不通时，它会先连 IPv6 一路等到超时，
     * 表现就是"一直黑屏然后报 IO 错"。OkHttp 有 Happy Eyeballs，会在两栈之间竞速。
     */
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android 的 org.json 在 JVM 单测中是空壳实现（一调用就抛 RuntimeException），
    // 这里给测试运行时补一个真实实现，否则所有解析类测试都无法执行。
    testImplementation("org.json:json:20240303")
}
