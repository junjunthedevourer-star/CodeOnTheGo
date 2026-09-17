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
// executable helper files under this module's build/intermediates/cxx tree. Keep
// those generated files in app-private HOME for on-device builds.
if (isTermuxJdk()) {
	layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/shizuku-manager"))
}

// Google's NDK archive installed by CoGo contains desktop host binaries. They cannot
// execute while Gradle is itself running on Android/ARM64. CoGo's terminal already
// provides native clang/LLVM plus the Android sysroot under PREFIX.
//
// Keep the NDK CMake toolchain as the source of truth for ABI/API flags and Prefab,
// but provide the host layout it expects. clang/clang++ are wrappers (not symlinks):
// executing the real PREFIX binaries preserves their real install/resource directory,
// which is required for compiler-rt. The legacy NDK toolchain also constructs one
// sysroot path without the host tag, so expose PREFIX at both expected locations.
val onDeviceNdkHostTag = "android-aarch64"
if (isTermuxJdk()) {
	val prefix = System.getenv("PREFIX")
		?: error("PREFIX is not set for on-device native build")
	val prefixDir = file(prefix)
	val prefixBin = prefixDir.resolve("bin")
	val ndkRoot = file("${System.getProperty("user.home")}/android-sdk/ndk/${BuildConfig.NDK_VERSION}")
	val prebuiltRoot = ndkRoot.resolve("toolchains/llvm/prebuilt")
	val hostRoot = prebuiltRoot.resolve(onDeviceNdkHostTag)
	val hostBin = hostRoot.resolve("bin")

	require(ndkRoot.isDirectory) { "Android NDK ${BuildConfig.NDK_VERSION} not found at $ndkRoot" }
	require(prefixBin.resolve("clang").canExecute()) { "Native clang not found at ${prefixBin.resolve("clang")}" }
	require(prefixBin.resolve("clang++").canExecute()) { "Native clang++ not found at ${prefixBin.resolve("clang++")}" }

	fun replaceWithSymlink(link: java.io.File, target: java.io.File) {
		if (Files.isSymbolicLink(link.toPath())) {
			if (Files.readSymbolicLink(link.toPath()) == target.toPath()) return
			Files.delete(link.toPath())
		} else if (link.exists()) {
			if (link.isDirectory && link.list()?.isEmpty() == true) {
				Files.delete(link.toPath())
			} else {
				error("Cannot create on-device NDK host shim: $link already exists")
			}
		}
		link.parentFile.mkdirs()
		Files.createSymbolicLink(link.toPath(), target.toPath())
	}

	// An older version of the shim linked the whole bin directory. Remove only that
	// known symlink before creating wrappers and per-tool links.
	if (Files.isSymbolicLink(hostBin.toPath())) {
		Files.delete(hostBin.toPath())
	}
	hostBin.mkdirs()

	fun writeCompilerWrapper(name: String) {
		val wrapper = hostBin.resolve(name)
		val realCompiler = prefixBin.resolve(name)
		wrapper.writeText("#!/system/bin/sh\nexec \"${realCompiler.absolutePath}\" \"\$@\"\n")
		require(wrapper.setExecutable(true, false) || wrapper.canExecute()) {
			"Unable to make compiler wrapper executable: $wrapper"
		}
	}
	writeCompilerWrapper("clang")
	writeCompilerWrapper("clang++")

	// Tools used by CMake/NDK after compiler detection. Link them individually so
	// clang itself never loses PREFIX-relative resource discovery.
	listOf("llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm", "llvm-objcopy", "llvm-objdump", "llvm-readelf", "ld.lld").forEach { name ->
		val target = prefixBin.resolve(name)
		if (target.exists()) replaceWithSymlink(hostBin.resolve(name), target)
	}

	replaceWithSymlink(hostRoot.resolve("sysroot"), prefixDir)
	replaceWithSymlink(prebuiltRoot.resolve("sysroot"), prefixDir)
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
