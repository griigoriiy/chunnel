import java.util.Properties

plugins {
    id("com.android.application") version "9.3.2" apply false
}

private fun sdkDirectory(): File? {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val properties = Properties().apply {
            localProperties.inputStream().use(::load)
        }
        properties.getProperty("sdk.dir")?.let(::File)?.takeIf(File::isDirectory)?.let { return it }
    }

    val userHome = System.getProperty("user.home")?.let(::File)
    return listOfNotNull(
        System.getenv("ANDROID_HOME")?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.let(::File),
        userHome?.resolve("Library/Android/sdk"),
        userHome?.resolve("Android/Sdk"),
    ).firstOrNull(File::isDirectory)
}

tasks.register("doctor") {
    group = "verification"
    description = "Checks the command-line Android build environment."

    doLast {
        val problems = mutableListOf<String>()
        val sdk = sdkDirectory()

        if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
            problems += "JDK 17 or newer is required; found ${JavaVersion.current()}."
        }

        if (sdk == null || !sdk.isDirectory) {
            problems += "Android SDK was not found. Set ANDROID_HOME or sdk.dir."
        } else {
            val requiredPaths = listOf(
                "platforms/android-36" to "platforms;android-36",
                "build-tools/36.0.0" to "build-tools;36.0.0",
                "platform-tools/adb" to "platform-tools",
            )
            requiredPaths.forEach { (path, packageName) ->
                if (!sdk.resolve(path).exists()) {
                    problems += "Missing Android SDK package: $packageName"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString(prefix = "Environment check failed:\n- ", separator = "\n- "))
        }

        logger.lifecycle("Environment is ready: JDK {} and Android SDK.", JavaVersion.current())
    }
}
