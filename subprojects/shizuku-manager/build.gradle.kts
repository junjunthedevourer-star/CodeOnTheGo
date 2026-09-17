@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.conf.isTermuxJdk
import java.nio.file.Files

plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
	id("dev.rikka.tools.refine")
	id("dev.rikka.tools.materialthemebuilder")
}

// Android shared storage is mounted noexec. AGP's native-build integration creates
// an executable prefab_command under this module's build/intermediates/cxx tree.
// Keep the module build directory in app-private HOME for on-device builds so the
// generated command can be executed by Gradle.
if (isTermuxJdk()) {
	layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/shizuku-manager"))
}

// Google's NDK archive installed by CoGo contains a linux-x86_64 host toolchain.
// That toolchain cannot execute when Gradle itself is running on an Android/ARM64
// device. CoGo already ships a native Android clang/LLVM toolchain and ndk-sysroot
// under PREFIX, so expose that existing toolchain to the NDK CMake integration via
// a small host shim. The NDK's own android.toolchain.cmake remains authoritative for
// ABI/API flags and Prefab integration; only the host executables/sysroot change.
val onDeviceNdkHostTag = "android-aarch64"
if (isTermuxJdk()) {
	val prefix = System.getenv("PREFIX")
		?: error("PREFIX is not set for on-device native build")
	val ndkRoot = file("${System.getProperty("user.home")}/android-sdk/ndk/${BuildConfig.NDK_VERSION}")
	val hostRoot = ndkRoot.resolve("toolchains/llvm/prebuilt/$onDeviceNdkHostTag")
	val prefixDir = file(prefix)
	val prefixBin = prefixDir.resolve("bin")

	require(ndkRoot.isDirectory) { "Android NDK ${BuildConfig.NDK_VERSION} not found at $ndkRoot" }
	require(prefixBin.resolve("clang").canExecute()) { "Native clang not found at ${prefixBin.resolve("clang")}" }
	require(prefixBin.resolve("clang++").canExecute()) { "Native clang++ not found at ${prefixBin.resolve("clang++")}" }

	hostRoot.mkdirs()
	fun ensureSymlink(link: java.io.File, target: java.io.File) {
		if (Files.isSymbolicLink(link.toPath())) {
			if (Files.readSymbolicLink(link.toPath()) == target.toPath()) return
			Files.delete(link.toPath())
		} else if (link.exists()) {
			error("Cannot create on-device NDK host shim: $link already exists and is not a symlink")
		}
		Files.createSymbolicLink(link.toPath(), target.toPath())
	}

	ensureSymlink(hostRoot.resolve("bin"), prefixBin)
	ensureSymlink(hostRoot.resolve("sysroot"), prefixDir)
	logger.lifecycle("Using native Android LLVM host shim at $hostRoot")
}

android {
	namespace = "moe.shizuku.manager"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
				if (isTermuxJdk()) {
					// The NDK legacy toolchain only assigns host tags for desktop Linux,
					// macOS and Windows. Preserve its normal logic while explicitly
					// selecting the native Android/ARM64 shim for an on-device build.
					arguments += "-DANDROID_HOST_TAG=$onDeviceNdkHostTag"
				}
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
