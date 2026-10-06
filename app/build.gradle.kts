plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.goutou.wingman"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.shibry88_netizen.talktact"
        minSdk = 31  // Android 12+：液态玻璃的真实背景模糊走 RenderEffect
        targetSdk = 34
        versionCode = 38
        versionName = "0.8.11"

        /**
         * 只打包 arm64-v8a（0.8.10 起）。
         *
         * 图片 OCR 用的 ML Kit 内置模型带一个 ~11MB 的原生库 `libmlkit_google_ocr_pipeline.so`，
         * 它给 4 个 ABI 各备了一份（合起来 40MB+）；这些 .so 在 APK 里是**不压缩**存放的，
         * 带上 2 份 arm 就要多出十几 MB。x86 / x86_64 是模拟器才有的东西，一直不带。
         *
         * ⚠️ 代价：**32 位老设备（armeabi-v7a）装不上这个包了**。想恢复 32 位支持，
         * 把 "armeabi-v7a" 加回下面那一行即可（微信跑在哪个 ABI、模块就跟着哪个）。
         */
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }
    /**
     * 发布签名：**密钥不进仓库**。
     *
     * 为什么要固定签名：GitHub Actions 每次跑在全新 runner 上，默认的 ~/.android/debug.keystore
     * 是每次重建的 —— 于是每个版本的签名都不一样，覆盖安装会直接报
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE (-7)。钥匙固定下来，本地和 CI 签出来的才是同一份。
     *
     * 钥匙在哪：只存在于 CI Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEYSTORE_ALIAS），
     * workflow 在构建前把它还原成 keystore/talktact.p12。想在本地签出同样的包，把该文件放回
     * keystore/ 并设置同名环境变量即可（用 KEYSTORE_PATH 可以指到别处）。
     * **文件缺失时不启用固定签名**，退回默认 debug 签名 —— 本地/下游构建不会因为缺钥匙而失败。
     *
     * ⚠️ 2026-10-05 换过钥匙：旧 key 连同口令曾提交在公开仓库里（commit 4f7b58e），等于公开私钥，已作废。
     * 签名变了 → 从 0.8.7 及更早版本升级的用户必须**卸载重装**（先在「设置 → 备份 / 迁移」导出备份）。
     */
    val keystoreFile = file(System.getenv("KEYSTORE_PATH") ?: "../keystore/talktact.p12")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val stableSigning = if (keystoreFile.exists() && !keystorePassword.isNullOrBlank()) {
        signingConfigs.create("stable") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "talktact"
            keyPassword = keystorePassword
            storeType = "PKCS12"
        }
    } else {
        null
    }
    buildTypes {
        debug { stableSigning?.let { signingConfig = it } }
        release {
            isMinifyEnabled = false
            stableSigning?.let { signingConfig = it }
        }
    }
    testOptions {
        unitTests {
            // Robolectric 要能读到 res/（字符串、颜色、drawable），否则 Compose 界面渲染不出来
            isIncludeAndroidResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 让 resources/ 下的 META-INF/xposed/* 原样进 APK —— 框架就是靠这三个文件认模块的，
        // 少一个 LSPosed 就当它不是模块（列表里都不出现）
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    // 注入到微信进程里的那部分（config/llm/wechat）刻意只用系统 API + kotlin stdlib，
    // 不引 OkHttp / 序列化库 —— 免得和微信自带的同名库撞车（parent-first 类加载会拿到它那份）。
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 折叠菜单的展开/收起动画（AnimatedVisibility）。material3/foundation 一般会把它带进来，
    // 但那是传递依赖，说不准哪天就没了 —— 这里显式写一条，版本仍由上面的 BOM 管。
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // 「每天中午 12:00 提炼说话风格」用它的周期任务：App 没开、手机重启过都照跑，
    // 也不必申请精确闹钟权限（AlarmManager 那条路 Android 12+ 要额外权限）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 图片文字识别（OCR）：ML Kit 的**内置**中文模型 —— 完全离线、不依赖 Play 服务、不要联网下载模型。
    // 它只在 **App 进程**里用（proxy/ProxyServer.kt 的 /proxy/ocr 那条路由），
    // 注入到微信里的那段代码绝不会碰到它 —— 模型和原生库太重，也不该塞进微信的进程。
    // 代价：APK 里多一份模型 + 一个原生库（ABI 已在 defaultConfig 里收窄，见上面的说明）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 现代 Xposed API（libxposed）。必须是 compileOnly：这些类由框架在运行时提供，
    // 打进 APK 反而会和框架自己那份撞车。
    compileOnly("io.github.libxposed:api:102.0.0")

    // service 是「模块 App ↔ 框架」的那一侧：写入 remote preferences 走它。
    // 必须是 implementation —— 它带一个 ContentProvider，运行时得真的存在。
    // 代价是它的 AAR 声明了 minCompileSdk=37，所以上面的 compileSdk 必须跟到 37。
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation("junit:junit:4.13.2")

    // 截图回归：在 JVM 上用 Robolectric 把真实界面渲染出来
    // （跑得比真机快、还能进 CI；渲染结果会作为 artifacts 上传，方便肉眼核对）
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    // ⚠️ 必须是 debugImplementation：它往**被测 APK 的 manifest** 里塞一个 ComponentActivity，
    // 而 createComposeRule() 要启动的就是它 —— 放 testImplementation 的话运行时找不到这个 Activity
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
