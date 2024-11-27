plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "tel.schich"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "tcp-wrapper"
            }
        }
    }
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(libs.ktorNetwork)
                implementation(libs.kotlinxCoroutinesCore)
                implementation(libs.kotlinxCoroutinesDebug)
                implementation(libs.kotlinxCli)
            }
        }
    }
}
