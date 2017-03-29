package com.beust.kobalt.app

import com.beust.kobalt.Args
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.KobaltContext
import com.beust.kobalt.api.Project
import com.beust.kobalt.homeDir
import com.beust.kobalt.internal.KobaltPluginXml
import com.beust.kobalt.internal.KobaltSettings
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.internal.build.BuildSources
import com.beust.kobalt.misc.*
import com.google.inject.Inject
import java.io.File
import java.net.URL
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * Parse all the files found in kobalt/src/ *kt, extract their buildScriptInfo blocks,
 * save the location where they appear (file, start/end).

 * Compile each of these buildScriptInfo separately, note which new build files they add
 * and at which location

 * Go back over all the files from kobalt/src/ *kt, insert each new build file in it,
 * save it as a modified, concatenated build file

 * Create buildScript.jar out of compiling all these modified build files
*/

fun main(argv: Array<String>) {
    val args = Args().apply {
        noIncrementalKotlin = true
    }
    val context = KobaltContext(args)
    KobaltLogger.LOG_LEVEL = 3
    context.pluginInfo = PluginInfo(KobaltPluginXml(), null, null)
    Kobalt.init(MainModule(args, KobaltSettings.readSettingsXml()))
    val bf = Kobalt.INJECTOR.getInstance(BuildFiles::class.java)
    bf.run(homeDir("kotlin/klaxon"), context)
}

class BuildFiles @Inject constructor(val factory: BuildFileCompiler.IFactory,
        val buildScriptUtil: BuildScriptUtil) {
    private val profileLines = arrayListOf<String>()
    private val activeProfiles = arrayListOf<String>()
    private val ROOT = File("kobalt/src")

    var containsProfiles = false
    val projects = arrayListOf<Project>()

    private fun findFiles(file: File, accept: (File) -> Boolean) : List<File> {
        val result = arrayListOf<File>()
        Files.walkFileTree(Paths.get(file.path), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                if (accept(file.toFile())) result.add(file.toFile())
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }

    class AnalyzedBuildFile(val file: File, val buildScriptInfo: BuildScriptInfo)

    fun run(projectDir: String, context: KobaltContext) {
        val analyzedFiles = parseBuildScriptInfos(projectDir, context)
        if (analyzedFiles.any()) {
            println("FOUND buildScriptInfos: " + analyzedFiles)
        } else {
            println("No buildScriptInfos")
        }
    }

    fun parseBuildScriptInfos(projectDir: String, context: KobaltContext) : List<AnalyzedBuildFile> {
        val root = File(projectDir, ROOT.path)
        val files = findFiles(root, { f: File -> f.name.endsWith(".kt")})
        val toProcess = arrayListOf<File>().apply { addAll(files) }

        // Parse each build file, associated it with a BuildScriptInfo if any found
        val analyzedFiles = arrayListOf<AnalyzedBuildFile>()
        toProcess.forEach { buildFile ->
            val lines = applyProfiles(context, buildFile.readLines())
            val bsi = BlockExtractor(Pattern.compile("^val.*buildScript.*\\{"), '{', '}')
                    .extractBlock(buildFile, lines)
            if (bsi != null) analyzedFiles.add(AnalyzedBuildFile(buildFile, bsi))
        }

        // Run every buildScriptInfo section in its own source file
        var counter = 0
        analyzedFiles.forEach { af ->
            af.buildScriptInfo.sections.forEach { section ->

                //
                // Create a source file with just this buildScriptInfo{}
                //
                val bs = af.file.readLines().subList(section.start - 1, section.end)
                val source = (af.buildScriptInfo.imports + bs).joinToString("\n")
                val sourceFile = File(homeDir("t", "bf", "a.kt")).apply {
                    writeText(source)
                }
                val buildScriptJarFile = File(homeDir("t", "preBuildScript-$counter.jar")).apply {
                    delete()
                }
                counter++

                //
                // Compile it to preBuildScript-xxx.jar
                //
                kobaltLog(1, "Compiling $sourceFile to $buildScriptJarFile")
                val taskResult = factory.create(BuildSources(root), context.pluginInfo).maybeCompileBuildFile(context,
                        listOf(sourceFile.path),
                        buildScriptJarFile, emptyList<URL>(),
                        context.internalContext.forceRecompile,
                        containsProfiles)
                println("Created $buildScriptJarFile")

                //
                // Run preBuildScript.jar to initialize plugins and repos
                //
                val currentDirs = arrayListOf<String>().apply { addAll(Kobalt.buildSourceDirs) }
                buildScriptUtil.runBuildScriptJarFile(buildScriptJarFile, listOf<URL>(), context)
                val newDirs = arrayListOf<String>().apply { addAll(Kobalt.buildSourceDirs) }
                newDirs.removeAll(currentDirs)
                if (newDirs.any()) {
                    af.buildScriptInfo.includedBuildSourceDirs.add(IncludedBuildSourceDir(section.start - 1, newDirs))
                    println("*** ADDED DIRECTORIES " + newDirs)
                }
            }

        }

        return analyzedFiles
    }

    private fun applyProfiles(context: KobaltContext, lines: List<String>): List<String> {
        val result = arrayListOf<String>()
        lines.forEach { line ->
            result.add(correctProfileLine(context, line))

        }
        return result
    }

    /**
     * If the current line matches one of the profiles, turn the declaration into
     * val profile = true, otherwise return the same line
     */
    fun correctProfileLine(context: KobaltContext, line: String): String {
        (context.profiles as List<String>).forEach { profile ->
            val re = Regex(".*va[rl][ \\t]+([a-zA-Z0-9_]+)[ \\t]*.*profile\\(\\).*")
            val oldRe = Regex(".*va[rl][ \\t]+([a-zA-Z0-9_]+)[ \\t]*=[ \\t]*[tf][ra][ul][es].*")
            val matcher = re.matchEntire(line)
            val oldMatcher = oldRe.matchEntire(line)

            fun profileMatch(matcher: MatchResult?) : Pair<Boolean, String?> {
                val variable = if (matcher != null) matcher.groups[1]?.value else null
                return Pair(profile == variable, variable)
            }

            if ((matcher != null && matcher.groups.isNotEmpty())
                    || (oldMatcher != null && oldMatcher.groups.isNotEmpty())) {
                containsProfiles = true
                val match = profileMatch(matcher)
                val oldMatch = profileMatch(oldMatcher)
                if (match.first || oldMatch.first) {
                    val variable = if (match.first) match.second else oldMatch.second

                    if (oldMatch.first) {
                        warn("Old profile syntax detected for \"$line\"," +
                                " please update to \"val $variable by profile()\"")
                    }

                    with("val $variable = true") {
                        kobaltLog(2, "Activating profile $profile in build file")
                        activeProfiles.add(profile)
                        profileLines.add(this)
                        return this
                    }
                }
            }
        }
        return line
    }
}