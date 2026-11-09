// [T-buildlogic-mirror] build-logic is a composite build: it is configured BEFORE
// src/android/settings.gradle.kts, so it cannot reuse minisCollectMavenMirrors()
// from there. Without a mirror here, mavenCentral() answers the 48 MB
// kotlin-compiler-embeddable jar with a 302 to github.com/JetBrains/kotlin/releases,
// and GitHub intermittently answers 504 — the plugin classpath then fails to
// resolve and the build dies at configuration time, before a single Kotlin source
// is compiled. Listing the CN mirrors first makes the jar arrive directly.
//
// The mirror list is duplicated inside both blocks on purpose: Gradle requires
// `pluginManagement {}` to be the FIRST statement in a settings script, so a
// top-level `val` above it is a hard error ("must appear before any other
// statements"), and a `fun` declared after it is an Unresolved reference from
// inside the early-evaluated block. Keep the two copies in step.
//
// Override with MINIS_BUILD_MIRRORS=off to use the upstream repos only.
pluginManagement {
    val mirrorUrls = run {
        val mode = (System.getenv("MINIS_BUILD_MIRRORS") ?: "auto").lowercase()
        val envUrls = (System.getenv("MINIS_MAVEN_URLS") ?: "")
            .split(',', ';')
            .map { it.trim() }
            .filter { it.startsWith("https://") }
        val cn = listOf(
            "https://maven.aliyun.com/repository/google",
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/gradle-plugin",
            "https://maven.aliyun.com/repository/central",
            "https://repo.huaweicloud.com/repository/maven",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        )
        if (mode == "off") envUrls else (envUrls + cn).distinct()
    }
    repositories {
        mirrorUrls.forEach { u -> maven { url = uri(u) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val mirrorUrls = run {
        val mode = (System.getenv("MINIS_BUILD_MIRRORS") ?: "auto").lowercase()
        val envUrls = (System.getenv("MINIS_MAVEN_URLS") ?: "")
            .split(',', ';')
            .map { it.trim() }
            .filter { it.startsWith("https://") }
        val cn = listOf(
            "https://maven.aliyun.com/repository/google",
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/gradle-plugin",
            "https://maven.aliyun.com/repository/central",
            "https://repo.huaweicloud.com/repository/maven",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        )
        if (mode == "off") envUrls else (envUrls + cn).distinct()
    }
    repositories {
        mirrorUrls.forEach { u -> maven { url = uri(u) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
