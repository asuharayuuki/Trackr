// 业务通用组件：KMP + Compose Multiplatform（Android + iosArm64 + iosSimulatorArm64）。
// 被两个以上 feature 用的东西放这里（重构计划 §2.2）：组件和轮询协调器等纯逻辑都在 commonMain；
// androidMain / iosMain 只放平台实现（剪贴板），androidMain 另有 LiveUpdateSettings 接口。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.trackr.kmp.compose)
}

kotlin {
    android {
        // Compose 资源（页面文案）在 Android 上走 Android 资源 / assets 打进 APK。
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:designsystem"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.material3)
            // :core:designsystem 的图标（Res.drawable.*，第 19 步乘车码按钮等 commonMain 组件用）
            implementation(libs.compose.mp.components.resources)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.material3)
            implementation(libs.androidx.compose.material.icons.core)
            implementation(libs.androidx.compose.material.icons.extended)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.core.ui.resources"
}
