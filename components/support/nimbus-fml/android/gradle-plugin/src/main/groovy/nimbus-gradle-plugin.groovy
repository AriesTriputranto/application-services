/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//package org.mozilla.experiments.nimbus-gradle-plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskProvider

abstract class NimbusPluginExtension {
    abstract Property<String> getManifestFile()

    String getManifestFileActual(Project project) {
        var filename = this.manifestFile.get() ?: "nimbus.fml.yaml"
        return [project.rootDir, filename].join(File.separator)
    }

    abstract MapProperty<String, String> getChannels()

    String getChannelActual(variant) {
        Map<String, String> channels = this.channels.get() ?: new HashMap()
        return channels.getOrDefault(variant.name, variant.name)
    }

    abstract Property<String> getDestinationPackage()

    def getPackageClassLiteral() {
        var fqnClass = destinationClass.get() ?: ".nimbus.MyNimbus"
        def i = fqnClass.lastIndexOf('.')
        switch (i) {
            case -1: return ["", fqnClass]
            case 0: return [".", fqnClass.substring(i + 1)]
            default: return [fqnClass.substring(0, i), fqnClass.substring(i + 1)]
        }
    }

    String getPackageNameActual(variant) {
        def (packageName, _) = getPackageClassLiteral()
        if (!packageName.startsWith(".")) {
            return packageName
        }
        TaskProvider buildConfigProvider = variant.getGenerateBuildConfigProvider()
        def configProvider = buildConfigProvider.get()
        def appPackageName
        // In Gradle 6.x `getBuildConfigPackageName` was replaced by `namespace`.
        // We want to be forward compatible, so we check that first or fallback to the old method.
        if (configProvider.hasProperty("namespace")) {
            appPackageName = configProvider.namespace.get()
        } else {
            appPackageName = configProvider.getBuildConfigPackageName().get()
        }
        if (packageName == ".") {
            return appPackageName
        } else {
            return appPackageName + packageName
        }
    }

    abstract Property<String> getDestinationClass()

    String getClassNameActual() {
        def (_, className) = getPackageClassLiteral()
        return className
    }

    abstract Property<String> getExperimenterManifest()

    String getExperimenterManifestActual(Project project) {
        var filename = this.experimenterManifest.get() ?: ".experimenter.json"
        return [project.rootDir, filename].join(File.separator)
    }
}

class NimbusPlugin implements Plugin<Project> {
    void apply(Project project) {
        def extension = project.extensions.create('nimbus', NimbusPluginExtension)

        if (project.hasProperty("android")) {
            if (project.android.hasProperty('applicationVariants')) {
                def doneOnce = false
                def experimenterTask = null
                project.android.applicationVariants.all { variant ->
                    def task = setupVariantTasks(variant, project, extension, false)

                    if (!doneOnce) {
                        // The extension doesn't seem to be ready until now, so we have this complicated
                        // doneOnce thing going on here. Ideally, we'd run this outside of this closure.
                        experimenterTask = setupExperimenterTasks(project, extension, false)
                        doneOnce = true
                    }

                    // Generating experimenter manifest is cheap, for now.
                    // So we generate this every time.
                    // In the future, we should try and make this an incremental task.
                    task.dependsOn(experimenterTask)
                }
            }

            if (project.android.hasProperty('libraryVariants')) {
                project.android.libraryVariants.all { variant ->
                    setupVariantTasks(variant, project, true)
                }
                // Experimenter works at the application level, not the library/project
                // level. The experimenter task for the application should gather up manifests
                // from its constituent libraries.
            }
        }
    }

    def getFMLPath(Project project) {
        String osPart
        String os = System.getProperty("os.name").toLowerCase()
        if (os.contains("win")) {
            osPart = "windows"
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            osPart = "linux"
        } else if (os.contains("mac")) {
            osPart = "macos"
        } else {
            osPart = "unknown"
        }

        String arch = System.getProperty("os.arch").toLowerCase()
        String archPart
        if (arch.contains("x86_64")) {
            archPart = "x86_64"
        } else {
            archPart = "unknown"
        }

        return [project.rootDir, "vendor", "bin", "${osPart}-${archPart}", "nimbus-fml"].join(File.separator)
    }

    def setupVariantTasks(variant, project, extension, isLibrary = false) {
        String packageName = extension.getPackageNameActual(variant)
        String className = extension.classNameActual

        String channel = extension.getChannelActual(variant)

        var parts = [project.buildDir, "generated", "source", "nimbus", variant.name, "kotlin"]
        var sourceOutputDir = parts.join(File.separator)

        parts.addAll(packageName.split("\\."))
        var outputDir = parts.join(File.separator)
        var outputFile = [outputDir, "${className}.kt"].join(File.separator)

        var inputFile = extension.getManifestFileActual(project)

        var generateTask = project.task("nimbusFeatures${variant.name.capitalize()}", type: Exec) {
            description = "Generate Kotlin data classes for Nimbus enabled features"
            group = "Nimbus"

            doFirst {
                ensureDirExists(new File(sourceOutputDir))
                ensureDirExists(new File(outputDir))
                println("Nimbus FML generating Kotlin")
                println("manifest   $inputFile")
                println("class      ${packageName}.${className}")
                println("channel    $channel")
            }

            doLast {
                println("outputFile $outputFile")
            }

            workingDir project.rootDir
            commandLine getFMLPath(project)
            args inputFile
            args "android", "features"
            args "--classname", className
            args "--output", outputFile
            args "--package", packageName
            args channel
        }
        variant.registerJavaGeneratingTask(generateTask, new File(sourceOutputDir))

        SourceTask generateSourcesTask = project.tasks.findByName("generate${variant.name.capitalize()}Sources")
        if (generateSourcesTask != null) {
            generateSourcesTask.dependsOn(generateTask)
        } else {
            SourceTask compileTask = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
            compileTask.dependsOn(generateTask)
        }

        return generateTask
    }

    def setupExperimenterTasks(project, extension, isLibrary = false) {
        if (isLibrary) {
            return
        }

        var inputFile = extension.getManifestFileActual(project)
        var outputFile = extension.getExperimenterManifestActual(project)

        return project.task("nimbusExperimenter", type: Exec) {
            description = "Generate feature manifest for Nimbus server (Experimenter)"
            group = "Nimbus"

            doFirst {
                println("Nimbus FML generating JSON")
                println("manifest   $inputFile")
            }

            doLast {
                println("outputFile $outputFile")
            }

            workingDir project.rootDir
            commandLine getFMLPath(project)
            args inputFile
            args "experimenter"
            args inputFile
            args "--output", outputFile
        }
    }

    def ensureDirExists(File dir) {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                dir.delete()
                dir.mkdirs()
            }
        } else {
            dir.mkdirs()
        }
    }
}

