package com.trello.victor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.file.DefaultSourceDirectorySet
import org.gradle.api.tasks.JavaExec

class VictorPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create('victor', VictorPluginExtension)

        project.configurations.create('batik')

        project.dependencies {
            batik 'org.apache.xmlgraphics:batik-codec:1.7'
            batik 'org.apache.xmlgraphics:batik-rasterizer:1.7'
        }

        // Add 'svg' as a source set extension
        project.android.sourceSets.all { sourceSet ->
            sourceSet.extensions.create('svg', DefaultSourceDirectorySet, 'svg', project.fileResolver)
        }

        project.afterEvaluate {
            Map densities = [
                    ldpi   : Math.round(project.victor.svgDpi * 0.5f),
                    mdpi   : project.victor.svgDpi,
                    hdpi   : Math.round(project.victor.svgDpi * 1.5f),
                    xhdpi  : project.victor.svgDpi * 2,
                    xxhdpi : project.victor.svgDpi * 3,
                    xxxhdpi: project.victor.svgDpi * 4
            ]

            project.victor.excludeDensities.each { density ->
                densities.remove(density)
            }

            // Register our task with the variant's resources
            project.android.applicationVariants.all { variant ->
                Set<File> srcDirs = new HashSet<>()
                variant.sourceSets.each { sourceSet ->
                    srcDirs.addAll(sourceSet.svg.srcDirs)
                }

                if (srcDirs.size() == 0) {
                    return
                }

                // Gather all available files
                // TODO: Use lazy evaluation?
                // TODO: Only accept SVG files that are named properly; reject (with warning) invalid resource names
                List<File> svgFiles = srcDirs.collect { srcDir ->
                    srcDir.listFiles(new FilenameFilter() {
                        @Override
                        boolean accept(File dir, String name) {
                            name.endsWith('.svg')
                        }
                    })
                }

                // If there are no files in a directory, it returns null
                svgFiles.removeAll {
                    it == null
                }

                svgFiles = svgFiles.flatten()

                if (svgFiles.size() == 0) {
                    return
                }

                // Overall task that we'll hook into
                Task rasterizatonTask = project.task("rasterizeSvgsFor${variant.name.capitalize()}") {
                    ext.outputDir = project.file("$project.buildDir/generated/res/$flavorName/$buildType.name/svg/")
                }

                // TODO: Create our own convert class instead of using Batik's tool, which kicks off JAR executable
                densities.keySet().each { density ->
                    // TODO: If rasterize needs to run again, clear out build directory first
                    File svgBuildDir = new File(rasterizatonTask.ext.outputDir, "drawable-$density")
                    Task rasterizeForDensity = project.task(
                            "rasterizeSvgsFor${variant.name.capitalize()}${density.capitalize()}", type: JavaExec) {
                        inputs.files svgFiles
                        outputs.dir svgBuildDir

                        main = 'org.apache.batik.apps.rasterizer.Main'
                        classpath = project.configurations.batik
                        args '-scriptSecurityOff'
                        args '-d', svgBuildDir.absolutePath
                        args '-dpi', densities[density]
                        args svgFiles
                    }

                    rasterizatonTask.dependsOn rasterizeForDensity
                }

                // Makes the magic happen (inserts resources so devs can use it)
                variant.registerResGeneratingTask(rasterizatonTask, rasterizatonTask.ext.outputDir)
            }
        }
    }
}