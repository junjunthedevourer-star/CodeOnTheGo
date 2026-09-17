package com.itsaky.androidide.build.config

import java.io.File

/**
 * Utilities for running shell commands.
 *
 * @author Akash Yadav
 */
object ShellUtils {
	/**
	 * Resolve an executable directly from PATH.
	 *
	 * Do not invoke the external `which` command here. On Android/Termux,
	 * Gradle's ProcessBuilder environment can have a valid PATH while the
	 * `which` utility itself is unavailable, causing on-device tools such as
	 * protoc to be incorrectly treated as missing.
	 */
	fun which(cmd: String): String? {
		if (cmd.isBlank() || cmd.contains(File.separatorChar)) {
			return null
		}

		val path = System.getenv("PATH") ?: return null
		return path
			.split(File.pathSeparatorChar)
			.asSequence()
			.filter { it.isNotBlank() }
			.map { File(it, cmd) }
			.firstOrNull { it.isFile && it.canExecute() }
			?.absolutePath
	}

	fun shC(
		cmd: String,
		workDir: File? = null,
		redirectErrorStream: Boolean = true,
	): String? {
		val proc =
			ProcessBuilder("sh", "-c", cmd).run {
				if (workDir != null) {
					directory(workDir)
				}

				redirectErrorStream(redirectErrorStream)
				start()
			}

		val exitCode = proc.waitFor()
		if (exitCode != 0) {
			return null
		}

		return proc.inputStream
			.bufferedReader()
			.readText()
			.trim()
	}
}
