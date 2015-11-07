package com.beust.kobalt

import com.beust.jcommander.JCommander
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.PluginInfo
import com.beust.kobalt.api.Project
import com.beust.kobalt.internal.TaskManager
import com.beust.kobalt.internal.remote.KobaltClient
import com.beust.kobalt.internal.remote.KobaltServer
import com.beust.kobalt.kotlin.BuildFile
import com.beust.kobalt.kotlin.BuildFileCompiler
import com.beust.kobalt.maven.DepFactory
import com.beust.kobalt.maven.Http
import com.beust.kobalt.maven.KobaltException
import com.beust.kobalt.maven.LocalRepo
import com.beust.kobalt.misc.*
import com.beust.kobalt.wrapper.Wrapper
import com.google.inject.Guice
import java.io.File
import java.nio.file.Paths
import java.util.*
import javax.inject.Inject

public fun main(argv: Array<String>) {
    val result = mainNoExit(argv)
    if (result != 0) {
        System.exit(result)
    }
}

private fun parseArgs(argv: Array<String>): Main.RunInfo {
    val args = Args()
    val result = JCommander(args)
    result.parse(*argv)
    KobaltLogger.LOG_LEVEL = args.log
    return Main.RunInfo(result, args)
}


public fun mainNoExit(argv: Array<String>) : Int {
    val (jc, args) = parseArgs(argv)
    Kobalt.INJECTOR = Guice.createInjector(MainModule(args))
    return Kobalt.INJECTOR.getInstance(Main::class.java).run(jc, args)
}

private class Main @Inject constructor(
        val buildFileCompilerFactory: BuildFileCompiler.IFactory,
        val plugins: Plugins,
        val taskManager: TaskManager,
        val http: Http,
        val files: KFiles,
        val executors: KobaltExecutors,
        val localRepo: LocalRepo,
        val depFactory: DepFactory,
        val checkVersions: CheckVersions,
        val github: GithubApi,
        val updateKobalt: UpdateKobalt,
        val client: KobaltClient,
        val server: KobaltServer,
        val pluginInfo: PluginInfo,
        val projectGenerator: ProjectGenerator) {

    data class RunInfo(val jc: JCommander, val args: Args)

    public fun run(jc: JCommander, args: Args) : Int {

        //
        // Add all the plugins read in plugin.xml to the Plugins singleton, so that code
        // in the build file that calls Plugins.findPlugin() can find them (code in the
        // build file do not have access to the KobaltContext).
        //
        pluginInfo.plugins.forEach { Plugins.addPluginInstance(it) }

        if (args.client) {
            client.run()
            return 0
        }

        var result = 0
        val latestVersionFuture = github.latestKobaltVersion
        benchmark("Build", {
//            runTest()
            try {
                result = runWithArgs(jc, args)
            } finally {
                executors.shutdown()
            }
        })

        log(1, if (result != 0) "BUILD FAILED: $result" else "BUILD SUCCESSFUL")

        // Check for new version
        val latestVersionString = latestVersionFuture.get()
        val latestVersion = Versions.toLongVersion(latestVersionString)
        val current = Versions.toLongVersion(Kobalt.version)
        if (latestVersion > current) {
            "***** ".let {
                log(1, it)
                log(1, "$it New Kobalt version available: $latestVersionString")
                log(1, "$it To update, run ./kobaltw --update")
                log(1, it )
            }
        }
        return result
    }


    public fun runTest() {
        val file = File("src\\main\\resources\\META-INF\\plugin.xml")
    }

    private fun runWithArgs(jc: JCommander, args: Args) : Int {
        var result = 0
        val p = if (args.buildFile != null) File(args.buildFile) else findBuildFile()
        args.buildFile = p.absolutePath
        val buildFile = BuildFile(Paths.get(p.absolutePath), p.name)

        if (! args.update) {
            println(AsciiArt.banner + Kobalt.version + "\n")
        }

        if (args.init) {
            //
            // --init: create a new build project and install the wrapper
            //
            Wrapper().install()
            projectGenerator.run(args)
        } else if (args.usage) {
            jc.usage()
        } else if (args.serverMode) {
            server.run()
        } else {
            if (! buildFile.exists()) {
                error(buildFile.path.toFile().path + " does not exist")
            } else {
                var allProjects = arrayListOf<Project>()
                try {
                    allProjects.addAll(buildFileCompilerFactory.create(listOf(buildFile), pluginInfo)
                            .compileBuildFiles(args))
                } catch(ex: KobaltException) {
                    throw ex
                } catch(ex: Throwable) {
                    // This can happen if the ABI for the build script file changed. Try to wipe .kobalt.
                    log(2, "Couldn't parse preBuildScript.jar: ${ex.message}")
                    if (! File(".kobalt").deleteRecursively()) {
                        warn("Couldn't delete the .kobalt directory, please delete it manually and try again")
                        return 1
                    } else {
                        log(1, "Deleted .kobalt")
                        allProjects.addAll(buildFileCompilerFactory.create(listOf(buildFile), pluginInfo)
                                .compileBuildFiles(args))
                    }
                }

                if (args.tasks) {
                    //
                    // List of tasks
                    //
                    val sb = StringBuffer("List of tasks\n")
                    Plugins.plugins.forEach { plugin ->
                        if (plugin.tasks.size > 0) {
                            sb.append("\n  ===== ${plugin.name} =====\n")
                            plugin.tasks.forEach { task ->
                                sb.append("    ${task.name}\t\t${task.doc}\n")
                            }
                        }
                    }
                    println(sb.toString())
                } else if (args.checkVersions) {
                    checkVersions.run(allProjects)
                } else if (args.update) {
                    updateKobalt.updateKobalt()
                } else {
                    //
                    // Launch the build
                    //
                    val thisResult = taskManager.runTargets(args.targets, allProjects)
                    if (result == 0) {
                        result = thisResult
                    }
                }
            }
        }
        return result
    }
    private fun findBuildFile(): File {
        val files = arrayListOf("Build.kt", "build.kobalt", KFiles.src("build.kobalt"),
                KFiles.src("Build.kt"))
        try {
            return files.map {
                File(SystemProperties.currentDir, it)
            }.first {
                it.exists()
            }
        } catch(ex: NoSuchElementException) {
            return File("Build.kt")
        }
    }
}

