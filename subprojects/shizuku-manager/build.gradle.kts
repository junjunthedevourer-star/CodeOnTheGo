@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.conf.isTermuxJdk
import com.itsaky.androidide.plugins.conf.prepareOnDeviceNdkHost

plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
	id("dev.rikka.tools.refine")
	id("dev.rikka.tools.materialthemebuilder")
}

// Android shared storage is noexec; AGP generates executable Prefab/CMake helpers.
if (isTermuxJdk()) {
	layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/shizuku-manager"))
}

// Shared with the two Termux native modules. Desktop builds use the stock NDK.
val onDeviceNdkHostTag = if (isTermuxJdk()) prepareOnDeviceNdkHost(BuildConfig.NDK_VERSION) else null

android {
	namespace = "moe.shizuku.manager"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
				onDeviceNdkHostTag?.let { arguments += "-DANDROID_HOST_TAG=$it" }
			}
		}
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
		prefab = true
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/jni/CMakeLists.txt")
			version = "3.31.0+"
			// AGP keeps .cxx outside layout.buildDirectory. Relocate its Ninja files
			// only for on-device builds; desktop builds retain the original staging.
			if (onDeviceNdkHostTag != null) {
				buildStagingDirectory = file("${System.getProperty("user.home")}/.cogo-cxx/shizuku-manager")
			}
		}
	}
}

dependencies {
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.recyclerview)

	implementation(projects.common)
	implementation(projects.logger)
	implementation(projects.resources)
	implementation(projects.subprojects.shizukuServer)
	implementation(projects.subprojects.shizukuStarter)
	implementation(projects.subprojects.shizukuApi)
	implementation(projects.subprojects.shizukuProvider)

	implementation(libs.rikka.hidden.compat)
	implementation(libs.rikkax.htmlktx)
	compileOnly(libs.rikka.hidden.stub)

	implementation(libs.libsu.core)
	implementation(libs.common.hiddenApiBypass)
	implementation(libs.boringssl)

	//noinspection UseTomlInstead
	implementation("org.lsposed.libcxx:libcxx:${BuildConfig.NDK_VERSION}")
}
