import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.conf.isTermuxJdk
import com.itsaky.androidide.plugins.conf.prepareOnDeviceNdkHost

plugins {
    id("com.android.library")
    id("kotlin-android")
}

val onDeviceNativeBuild = isTermuxJdk()
if (onDeviceNativeBuild) {
    // AGP emits executable CMake intermediates: Android shared storage is noexec.
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/termux-emulator"))
}
val onDeviceNdkHostTag = if (onDeviceNativeBuild) prepareOnDeviceNdkHost(BuildConfig.NDK_VERSION) else null

android {
    namespace = "com.termux.emulator"
    ndkVersion = BuildConfig.NDK_VERSION

    defaultConfig {
        externalNativeBuild {
            if (onDeviceNativeBuild) {
                cmake {
                    arguments += "-DANDROID_STL=none"
                    arguments += "-DANDROID_HOST_TAG=$onDeviceNdkHostTag"
                }
            } else {
                ndkBuild {
                    cFlags += arrayOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
                }
            }
        }
    }

    externalNativeBuild {
        if (onDeviceNativeBuild) {
            // Same source and same libtermux.so JNI library as the Android.mk target.
            // Google's ndk-build rejects aarch64 hosts before parsing Android.mk.
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.31.0+"
            }
        } else {
            ndkBuild {
                path = file("src/main/jni/Android.mk")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType(Test::class.java) {
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(projects.testing.unit)
}
