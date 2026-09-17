package com.itsaky.androidide.plugins.conf

import org.gradle.api.Project
import java.io.File
import java.nio.file.Files

/**
 * Supply the Android/ARM64 host layout required by the standard NDK CMake toolchain.
 * This does not run ndk-build: Google's ndk-build makefiles reject aarch64 hosts.
 * Keep target ABI, platform and Prefab handling in the NDK's own CMake toolchain.
 * Call only when running Gradle under CoGo's Termux JDK.
 */
private val onDeviceNdkLock = Any()

fun Project.prepareOnDeviceNdkHost(ndkVersion: String): String = synchronized(onDeviceNdkLock) {
    val hostTag = "android-aarch64"
    val prefix = System.getenv("PREFIX")
        ?: error("PREFIX is not set for on-device native build")
    val prefixDir = file(prefix)
    val prefixBin = prefixDir.resolve("bin")
    val ndkRoot = file("${System.getProperty("user.home")}/android-sdk/ndk/$ndkVersion")
    val prebuiltRoot = ndkRoot.resolve("toolchains/llvm/prebuilt")
    val hostRoot = prebuiltRoot.resolve(hostTag)
    val hostBin = hostRoot.resolve("bin")

    require(ndkRoot.isDirectory) { "Android NDK $ndkVersion not found at $ndkRoot" }
    require(prefixBin.resolve("clang").canExecute()) { "Native clang not found at ${prefixBin.resolve("clang")}" }
    require(prefixBin.resolve("clang++").canExecute()) { "Native clang++ not found at ${prefixBin.resolve("clang++")}" }

    fun replaceWithSymlink(link: File, target: File) {
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

    // The previous shim linked the entire bin directory. Replace that link only;
    // a compiler symlink would change Clang's resource-dir lookup to an invalid NDK path.
    if (Files.isSymbolicLink(hostBin.toPath())) Files.delete(hostBin.toPath())
    hostBin.mkdirs()

    fun writeCompilerWrapper(name: String) {
        val wrapper = hostBin.resolve(name)
        if (Files.isSymbolicLink(wrapper.toPath())) Files.delete(wrapper.toPath())
        val realCompiler = prefixBin.resolve(name)
        wrapper.writeText("#!/system/bin/sh\nexec \"${realCompiler.absolutePath}\" \"\$@\"\n")
        require(wrapper.setExecutable(true, false) || wrapper.canExecute()) {
            "Unable to make compiler wrapper executable: $wrapper"
        }
    }
    writeCompilerWrapper("clang")
    writeCompilerWrapper("clang++")

    listOf("llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm", "llvm-objcopy", "llvm-objdump", "llvm-readelf", "ld.lld")
        .forEach { name ->
            val target = prefixBin.resolve(name)
            if (target.exists()) replaceWithSymlink(hostBin.resolve(name), target)
        }

    replaceWithSymlink(hostRoot.resolve("sysroot"), prefixDir)
    // The legacy NDK CMake toolchain also constructs the path without a host tag.
    replaceWithSymlink(prebuiltRoot.resolve("sysroot"), prefixDir)
    logger.lifecycle("Using native Android LLVM host shim at $hostRoot")
    hostTag
}
