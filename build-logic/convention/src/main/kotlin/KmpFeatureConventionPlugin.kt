import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * trackr.kmp.feature：功能模块（`:feature:*`）= `trackr.kmp.library` + `trackr.kmp.compose` + 共用依赖。
 *
 * - commonMain：`:core:*`（feature 只依赖 core，不互相依赖）、CMP、KMP 版 ViewModel / lifecycle-compose、Koin。
 *   ViewModel、UiState、页面、`xxxEntries()` 都放这里。
 * - androidMain：Jetpack Compose（Material 3、图标）。只放平台实现（`:feature:route` 的高德 SDK 和地图、
 *   `:feature:search` 的语音输入），页面不放这里。
 * - 测试：commonTest（kotlin.test + `:core:testing` 的 Fake + coroutines-test）、androidHostTest（Robolectric + Compose UI 测试）。
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("trackr.kmp.library")
            pluginManager.apply("trackr.kmp.compose")

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("commonMain").dependencies {
                    implementation(project(":core:model"))
                    implementation(project(":core:domain"))
                    implementation(project(":core:data"))
                    implementation(project(":core:navigation"))
                    implementation(project(":core:designsystem"))
                    implementation(project(":core:ui"))
                    implementation(libs.lib("compose-mp-runtime"))
                    implementation(libs.lib("compose-mp-foundation"))
                    implementation(libs.lib("compose-mp-ui"))
                    implementation(libs.lib("compose-mp-animation"))
                    implementation(libs.lib("compose-mp-material3"))
                    implementation(libs.lib("jetbrains-lifecycle-viewmodel"))
                    implementation(libs.lib("jetbrains-lifecycle-runtime-compose"))
                    implementation(libs.lib("kotlinx-coroutines-core"))
                    implementation(libs.lib("kotlinx-datetime"))
                    implementation(project.dependencies.platform(libs.lib("koin-bom")))
                    implementation(libs.lib("koin-core"))
                    implementation(libs.lib("koin-compose"))
                    implementation(libs.lib("koin-compose-viewmodel"))
                }
                sourceSets.getByName("androidMain").dependencies {
                    implementation(project.dependencies.platform(libs.lib("androidx-compose-bom")))
                    implementation(libs.lib("androidx-compose-ui"))
                    implementation(libs.lib("androidx-compose-material3"))
                    implementation(libs.lib("androidx-compose-material-icons-core"))
                    implementation(libs.lib("androidx-compose-material-icons-extended"))
                    implementation(libs.lib("androidx-compose-ui-tooling-preview"))
                    implementation(libs.lib("androidx-activity-compose"))
                    implementation(libs.lib("androidx-lifecycle-runtime-compose"))
                    implementation(libs.lib("androidx-core-ktx"))
                    // androidMain 里的 Koin 平台模块要 androidContext()
                    implementation(project.dependencies.platform(libs.lib("koin-bom")))
                    implementation(libs.lib("koin-android"))
                }
                sourceSets.getByName("commonTest").dependencies {
                    implementation(kotlin("test"))
                    implementation(project(":core:testing"))
                    implementation(libs.lib("kotlinx-coroutines-test"))
                    implementation(libs.lib("turbine"))
                }
                sourceSets.getByName("androidHostTest").dependencies {
                    implementation(kotlin("test-junit"))
                    implementation(libs.lib("junit"))
                    implementation(libs.lib("robolectric"))
                    implementation(project.dependencies.platform(libs.lib("androidx-compose-bom")))
                    implementation(libs.lib("androidx-compose-ui-test-junit4"))
                    implementation(libs.lib("androidx-compose-ui-test-manifest"))
                    runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
                }
            }
        }
    }
}

private fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()
