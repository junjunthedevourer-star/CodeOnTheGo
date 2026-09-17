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

    // The compiler executes on Android from PREFIX, but target headers, CRT files,
    // compiler-rt, libatomic and libunwind must all come from the same NDK revision.
    // PREFIX is laid out differently and has a different Clang resource directory.
    val ndkDesktopHost = prebuiltRoot.resolve("linux-x86_64")
    val ndkTargetSysroot = ndkDesktopHost.resolve("sysroot")
    val ndkClangResourceDir = ndkDesktopHost.resolve("lib/clang").listFiles()
        ?.filter { candidate ->
            candidate.isDirectory &&
                candidate.resolve("lib/linux/aarch64/libatomic.a").isFile &&
                candidate.resolve("lib/linux/aarch64/libunwind.a").isFile &&
                candidate.resolve("lib/linux/libclang_rt.builtins-aarch64-android.a").isFile
        }
        ?.maxByOrNull { it.name.toIntOrNull() ?: -1 }
        ?: error("Cannot find NDK ARM64 compiler-rt, libatomic and libunwind archives under ${ndkDesktopHost.resolve("lib/clang")}")

    require(ndkRoot.isDirectory) { "Android NDK $ndkVersion not found at $ndkRoot" }
    require(ndkTargetSysroot.resolve("usr/include/dirent.h").isFile) {
        "NDK target sysroot headers not found at $ndkTargetSysroot"
    }
    require(ndkTargetSysroot.resolve("usr/include/aarch64-linux-android/asm/types.h").isFile) {
        "NDK ARM64 target headers not found at $ndkTargetSysroot"
    }
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
        // Select the NDK archive directory per target ABI. Add -L only when
        // linking: Clang can warn about unused linker flags in -Werror -c builds.
        // -resource-dir also redirects implicit builtins/unwind lookup to the NDK.
        val runtimeRoot = ndkClangResourceDir.resolve("lib/linux")
        wrapper.writeText(
            "#!/system/bin/sh\n" +
                "ndk_runtime_arch=aarch64\n" +
                "ndk_compile_only=0\n" +
                "for ndk_arg in \"\$@\"; do\n" +
                "  case \"\$ndk_arg\" in\n" +
                "    --target=arm*|-target=arm*) ndk_runtime_arch=arm ;;\n" +
                "    --target=aarch64*|-target=aarch64*) ndk_runtime_arch=aarch64 ;;\n" +
                "    -c|-E|-S|-fsyntax-only) ndk_compile_only=1 ;;\n" +
                "  esac\n" +
                "done\n" +
                "if [ \"\$ndk_compile_only\" -eq 1 ]; then\n" +
                "  exec \"${realCompiler.absolutePath}\" -resource-dir \"${ndkClangResourceDir.absolutePath}\" \"\$@\"\n" +
                "fi\n" +
                "exec \"${realCompiler.absolutePath}\" -resource-dir \"${ndkClangResourceDir.absolutePath}\" -L\"${runtimeRoot.absolutePath}/\$ndk_runtime_arch\" \"\$@\"\n"
        )
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

    replaceWithSymlink(hostRoot.resolve("sysroot"), ndkTargetSysroot)
    // The legacy NDK CMake toolchain also constructs this path without a host tag.
    replaceWithSymlink(prebuiltRoot.resolve("sysroot"), ndkTargetSysroot)
    logger.lifecycle("Using native Android LLVM host shim at $hostRoot with NDK sysroot $ndkTargetSysroot and compiler resources $ndkClangResourceDir")
    hostTag
}
