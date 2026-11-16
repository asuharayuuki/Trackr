// :feature:favorites：编辑收藏（重构计划 §2.2）。ViewModel、页面、Koin 模块、entry 都在 commonMain（第 22 步），没有 androidMain。
// 共用依赖由约定插件 trackr.kmp.feature 提供。
plugins {
    alias(libs.plugins.trackr.kmp.feature)
}

kotlin {
    android {
        // 主机测试里也要能读到 :core:designsystem 的 Compose 资源（图标）
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            // 第 22 步：编辑收藏页进 commonMain，图标用 :core:designsystem 的 Compose 资源
            implementation(libs.compose.mp.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.favorites.resources"
}
