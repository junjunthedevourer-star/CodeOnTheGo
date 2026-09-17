import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.conf.isTermuxJdk
import com.itsaky.androidide.plugins.conf.prepareOnDeviceNdkHost

plugins {
    id("com.android.library")
    id("kotlin-android")
}

val onDeviceNativeBuild = isTermuxJdk()
if (onDeviceNativeBuild) {
    // AGP's CMake and Prefab helper scripts need executable private storage.
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/termux-shared"))
}
val onDeviceNdkHostTag = if (onDeviceNativeBuild) prepareOnDeviceNdkHost(BuildConfig.NDK_VERSION) else null

android {
    namespace = "com.termux.shared"
    ndkVersion = BuildConfig.NDK_VERSION

    if (onDeviceNativeBuild) {
        // Same static C++ runtime package and CMake integration used by Shizuku.
        // Avoid the NDK's x86-host-only ndk-build runner without changing JNI code.
        buildFeatures.prefab = true
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_STL=none"
                    arguments += "-DANDROID_HOST_TAG=$onDeviceNdkHostTag"
                }
            }
        }
    }

    externalNativeBuild {
        if (onDeviceNativeBuild) {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.0+"
                // layout.buildDirectory does not relocate AGP's separate .cxx staging.
                // Keep build.ninja and CMake's regeneration timestamps off shared storage.
                buildStagingDirectory = file("${System.getProperty("user.home")}/.cogo-cxx/termux-shared")
            }
        } else {
            ndkBuild {
                path = file("src/main/cpp/Android.mk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.window.v1alpha9)
    implementation(libs.common.markwon.core)
    implementation(libs.common.markwon.extStrikethrough)
    implementation(libs.common.markwon.linkify)
    implementation(libs.common.markwon.recycler)
    implementation(libs.google.material)
    implementation(libs.google.guava)
    implementation(libs.common.hiddenApiBypass)

    // Do not increment version higher than 1.0.0-alpha09 since it will break ViewUtils and needs to be looked into
    // noinspection GradleDependency
    implementation(libs.common.io)
    implementation(libs.common.termuxAmLib)

    implementation(projects.common)
    implementation(projects.termux.termuxView)
    implementation(projects.buildInfo)
    implementation(projects.preferences)
    implementation(projects.resources)

    // The on-device CMake target links Prefab's static libc++ equivalent to
    // Application.mk's APP_STL=c++_static. Do not package a second shared STL.
    if (onDeviceNativeBuild) {
        //noinspection UseTomlInstead
        implementation("org.lsposed.libcxx:libcxx:${BuildConfig.NDK_VERSION}")
    }

    testImplementation(projects.testing.unit)
    testImplementation(projects.testing.android)
}
