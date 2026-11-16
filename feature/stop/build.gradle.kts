// :feature:stop：站点详情（重构计划 §2.2）。ViewModel、页面、Koin 模块、entry 都在 commonMain（第 21 步），没有 androidMain。
// 共用依赖由约定插件 trackr.kmp.feature 提供。
plugins {
    alias(libs.plugins.trackr.kmp.feature)
}

kotlin {
    android {
        // Compose 资源（页面文案）在 Android 上走 Android 资源 / assets 打进 APK。
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.mp.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.stop.resources"
}
