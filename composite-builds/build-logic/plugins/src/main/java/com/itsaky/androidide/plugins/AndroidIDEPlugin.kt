/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins

import com.android.build.gradle.BaseExtension
import com.itsaky.androidide.build.config.isFDroidBuild
import com.itsaky.androidide.plugins.conf.isTermuxJdk
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Manages plugins applied on the IDE's project modules.
 *
 * @author Akash Yadav
 */
class AndroidIDEPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.run {
      if (project.path == rootProject.path) {
        throw GradleException("Cannot apply ${AndroidIDEPlugin::class.simpleName} to root project")
      }

      if (!project.buildFile.exists() || !project.buildFile.isFile) {
        return@run
      }

      val isAndroidModule = plugins.hasPlugin("com.android.application") ||
          plugins.hasPlugin("com.android.library")

      if (isAndroidModule && isTermuxJdk()) {
        // AGP 8.8 defaults to Build Tools 35.0.0. The SDK package installed by
        // the on-device toolchain contains an x86-64 AIDL binary there, which
        // cannot execute on Android/ARM64. Build Tools 36.0.0 contains the
        // native AArch64 AIDL used by Code on the Go. Select it for every
        // Android module so AIDL compilation is reproducible on-device without
        // modifying SDK binaries. Desktop/CI builds keep AGP's normal choice.
        val baseExtension = extensions.getByType(BaseExtension::class.java)
        baseExtension.buildToolsVersion = "36.0.0"
        logger.lifecycle("Using Android Build Tools 36.0.0 for on-device build in ${project.path}")
      }

      if (isAndroidModule && !isFDroidBuild) {
        // setup signing configuration
        plugins.apply(SigningConfigPlugin::class.java)
      }

      if (isFDroidBuild && project.path == ":app") {
        val baseExtension = extensions.getByType(BaseExtension::class.java)
        logger.warn("Building for F-Droid with configuration:")
        logger.warn("applicationId = ${baseExtension.defaultConfig.applicationId}")
        logger.warn("versionName = ${baseExtension.defaultConfig.versionName}")
        logger.warn("versionCode = ${baseExtension.defaultConfig.versionCode}")
        logger.warn("--- x --- x ---")
      }

      val taskName = when {
        isAndroidModule -> "testDebugUnitTest"
        else -> "test"
      }

      logger.info("${project.path} will run task '$taskName' for tests in CI")

      project.tasks.create("runTestsInCI") {
        dependsOn(taskName)
      }
    }
  }
}
