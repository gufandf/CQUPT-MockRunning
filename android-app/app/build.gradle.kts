/*
 * 校园跑——重邮智慧体育打卡工具（单机版）
 */
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ──────────────────────────────────────────────────────────────
// 版本号：唯一来源是 android-app/version.properties，**不在这个文件里手写**。
//
// 以前 versionCode / versionName 直接写死在这里，每加一点功能就顺手 +1，
// 结果版本号涨得比内容快（19 个小版本里有一半只是文案和间距调整）。
// 现在拆出去以后：
//   · 日常改代码**不碰版本号**，改了也没人读；
//   · 只有明确说「发版」时才改 version.properties（或跑 release.py）；
//   · versionCode 由 major/minor/patch 按 MAJOR*10000 + MINOR*100 + PATCH 算出来，
//     不会再出现「versionName 和 versionCode 对不上」。
//
// AGP 从 8.x 起支持 lazy 变量：如果需要 RC 号，可以在这里拼，
// 但本项目的规则是「一个版本一个 tag」，不搞 pre-release，所以保持三段的简单形态。
//
// version.prefix 是 versionName 的纯展示前缀（可空，见 version.properties 注释）：
// 它只拼在版本号前面，**不参与 versionCode**，versionCode 永远是纯数字公式。
// ──────────────────────────────────────────────────────────────
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val vPrefix = (versionProps.getProperty("version.prefix") ?: "").trim()
val vMajor = (versionProps.getProperty("version.major") ?: "0").trim().toInt()
val vMinor = (versionProps.getProperty("version.minor") ?: "0").trim().toInt()
val vPatch = (versionProps.getProperty("version.patch") ?: "0").trim().toInt()
// prefix 为空时就是纯数字的 1.11.1
val vName = vPrefix + "$vMajor.$vMinor.$vPatch"
val vCode = vMajor * 10000 + vMinor * 100 + vPatch

android {
    namespace = "com.whale.campusrun"
    // 36 (Android 16)：流体云 / 胶囊走的是系统 Live Updates（ProgressStyle +
    // 提升为实时活动），这套 API 到 API 36 才有，所以 compileSdk 必须跟上。
    // targetSdk 仍是 34 —— 没有为 35/36 的行为变更做适配，不冒充。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.whale.campusrun"
        minSdk = 24
        targetSdk = 34
        // 值来自 android-app/version.properties，规则见 AGENT.md §5.1
        versionCode = vCode
        versionName = vName
        // 清单里的 <meta-data android:name="version"> 也从这里取，
        // 免得改版本号还要记得同步第二个文件（以前就漏过一次）。
        manifestPlaceholders["appVersion"] = vName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 自用，直接用 debug 签名，免得配 keystore
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true      // 让 MainActivity 能读到 BuildConfig.VERSION_NAME
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 1.17 才有 NotificationCompat.ProgressStyle / setRequestPromotedOngoing /
    // setShortCriticalText —— 流体云胶囊靠这三样（要求 compileSdk 36）
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.material:material:1.12.0")

    // TrackGen 是纯 JVM 逻辑，直接用单元测试验证配速标定与围栏判定
    testImplementation("junit:junit:4.13.2")
}

// 快速确认「现在这个构建会打出什么版本号」，不用真的等一次完整构建：
//   ./gradlew :app:ver
tasks.register("ver") {
    group = "help"
    description = "打印当前 versionName / versionCode（来自 version.properties）"
    doLast { println("versionName = $vName   versionCode = $vCode") }
}



