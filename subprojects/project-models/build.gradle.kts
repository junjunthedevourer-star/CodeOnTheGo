import com.google.protobuf.gradle.id
import com.itsaky.androidide.plugins.conf.configureProtoc
import com.itsaky.androidide.plugins.conf.isTermuxJdk

plugins {
	id("java-library")
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.google.protobuf)
}

// Android shared storage is mounted noexec. The protobuf Gradle plugin creates an
// executable trampoline script for JAR-based protoc plugins under build/scripts.
// Keep this module's build directory in the app-private HOME when building with
// the Termux JDK so protoc can execute protoc-gen-kotlin-ext normally.
if (isTermuxJdk()) {
	layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cogo-build/project-models"))
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
