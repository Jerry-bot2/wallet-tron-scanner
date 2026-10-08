pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "wallet-tron-scanner"

val useLocalNbCommon = providers.gradleProperty("useLocalNbCommon")
    .orElse(providers.environmentVariable("USE_LOCAL_NB_COMMON"))
    .map(String::toBoolean)
    .getOrElse(false)

if (useLocalNbCommon) {
    val localNbCommon = file("../../uuwallet/nb-common")
    check(localNbCommon.resolve("settings.gradle.kts").isFile) {
        "启用本地 nb-common 源码联调时，必须存在 ../../uuwallet/nb-common 项目"
    }
    includeBuild(localNbCommon)
}

val useLocalTronSdk = providers.gradleProperty("useLocalTronSdk")
    .orElse(providers.environmentVariable("USE_LOCAL_TRON_SDK"))
    .map(String::toBoolean)
    .getOrElse(false)

if (useLocalTronSdk) {
    val localTronSdk = file("../wallet-tron-sdk")
    check(localTronSdk.resolve("settings.gradle.kts").isFile) {
        "启用本地TRON SDK源码联调时，必须存在 ../wallet-tron-sdk 项目"
    }
    includeBuild(localTronSdk)
}
