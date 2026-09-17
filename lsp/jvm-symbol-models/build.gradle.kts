import com.google.protobuf.gradle.id
import com.itsaky.androidide.plugins.conf.configureProtoc
import java.io.File

plugins {
	id("java-library")
	id("org.jetbrains.kotlin.jvm")
	alias(libs.plugins.google.protobuf)
}

// Android shared storage (/storage/emulated/0) is mounted noexec. The protobuf Gradle plugin
// materializes executable trampoline scripts for Java-based protoc plugins under build/scripts,
// so protoc cannot launch kotlin-ext when this repository lives in CoGo's shared project folder.
// Keep normal desktop builds unchanged and relocate this module's generated build files into the
// app-private, executable cache only for Termux/CoGo JVMs. Include the checkout path hash so
// independently cloned CoGo variants cannot share generated state.
if (
	System.getProperty("java.vendor") == "Termux" ||
	System.getProperty("java.vm.vendor") == "Termux"
) {
	val checkoutId = rootProject.projectDir.absolutePath.hashCode().toUInt().toString(16)
	val privateBuildDir =
		File(
			System.getProperty("user.home"),
			".cache/cogo-gradle-build/$checkoutId/lsp-jvm-symbol-models",
		)
	layout.buildDirectory.set(privateBuildDir)
	logger.lifecycle("Using executable private build directory for jvm-symbol-models: $privateBuildDir")
}

configureProtoc(protobuf = protobuf, protocVersion = libs.versions.protobuf.asProvider())

protobuf {
	plugins {
		id("kotlin-ext") {
			artifact = "dev.hsbrysk:protoc-gen-kotlin-ext:${libs.versions.protoc.gen.kotlin.ext.get()}:jdk8@jar"
		}
	}
	generateProtoTasks {
		all().forEach { task ->
			task.plugins {
				id("kotlin-ext") {
					outputSubDir = "kotlin"
				}
			}
			task.builtins {
				getByName("java") {
					option("lite")
				}
			}
		}
	}
}

dependencies {
	api(libs.google.protobuf.java)
	api(libs.google.protobuf.kotlin)
}
