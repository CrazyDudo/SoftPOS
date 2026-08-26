pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SoftPOS"

// ':emv-core' is a plain Kotlin/JVM module with no Android dependency. It holds every piece of
// EMV logic that can be exercised without a phone, so it must stay buildable on machines and CI
// runners that have no Android SDK installed.
include(":emv-core")

// The Android modules are only wired in when an SDK is actually reachable. Without this guard the
// Android Gradle Plugin fails during configuration and takes ':emv-core:test' down with it.
// Force them on with: -PforceAndroidModules=true
val androidSdkLocation: String? = sequenceOf(
    providers.gradleProperty("sdk.dir").orNull,
    providers.environmentVariable("ANDROID_HOME").orNull,
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
    file("local.properties")
        .takeIf { it.isFile }
        ?.let { java.util.Properties().apply { it.inputStream().use(::load) }.getProperty("sdk.dir") },
).filterNotNull().firstOrNull { File(it).isDirectory }

val forceAndroid = providers.gradleProperty("forceAndroidModules").orNull.toBoolean()

if (androidSdkLocation != null || forceAndroid) {
    include(":softpos-sdk")
    include(":demo")
} else {
    logger.lifecycle(
        """
        |
        |  No Android SDK found - configuring ':emv-core' only.
        |  ':softpos-sdk' and ':demo' were skipped.
        |
        |  To build them, set ANDROID_HOME / ANDROID_SDK_ROOT, or add sdk.dir to local.properties,
        |  or open this project in Android Studio (which writes local.properties for you).
        |
        """.trimMargin(),
    )
}
