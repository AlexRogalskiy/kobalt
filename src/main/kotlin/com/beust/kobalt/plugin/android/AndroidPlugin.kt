package com.beust.kobalt.plugin.android

import com.beust.kobalt.*
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.Directive
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.maven.MavenId
import com.beust.kobalt.maven.dependency.FileDependency
import com.beust.kobalt.maven.dependency.MavenDependency
import com.beust.kobalt.misc.*
import com.beust.kobalt.plugin.java.JavaCompiler
import com.beust.kobalt.plugin.packaging.JarUtils
import com.google.common.collect.HashMultimap
import com.google.inject.Inject
import com.google.inject.Singleton
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.nio.file.Paths

@Singleton
public class AndroidPlugin @Inject constructor(val javaCompiler: JavaCompiler, val merger: Merger,
        val executors: KobaltExecutors)
            : ConfigPlugin<AndroidConfig>(), IClasspathContributor, IRepoContributor, ICompilerFlagContributor,
                ICompilerInterceptor, IBuildDirectoryIncerceptor, IRunnerContributor, IClasspathInterceptor,
                ISourceDirectoryContributor, IBuildConfigFieldContributor, ITaskContributor {
    companion object {
        const val PLUGIN_NAME = "Android"
        const val TASK_GENERATE_DEX = "generateDex"
        const val TASK_SIGN_APK = "signApk"
        const val TASK_INSTALL= "install"
    }

    override val name = PLUGIN_NAME

    fun isAndroid(project: Project) = configurationFor(project) != null

    val taskContributor : TaskContributor = TaskContributor()

    override fun apply(project: Project, context: KobaltContext) {
        super.apply(project, context)
        if (accept(project)) {
            project.compileDependencies.add(FileDependency(androidJar(project).toString()))

            taskContributor.addVariantTasks(project, context, "generateR", runBefore = listOf("compile"),
                    runTask = { taskGenerateRFile(project) })
            taskContributor.addVariantTasks(project, context, "generateDex", runAfter = listOf("compile"),
                    runBefore = listOf("assemble"),
                    runTask = { taskGenerateDex(project) })
            taskContributor.addVariantTasks(project, context, "signApk", runAfter = listOf("generateDex"),
                    runBefore = listOf("assemble"),
                    runTask = { taskSignApk(project) })
            taskContributor.addVariantTasks(project, context, "install", runAfter = listOf("signApk"),
                    runTask = { taskInstall(project) })
            taskContributor.addVariantTasks(project, context, "proguard", runBefore = listOf("install"),
                    runAfter = listOf("compile"),
                    runTask = { taskProguard(project) })
        }
        context.pluginInfo.classpathContributors.add(this)
    }


    override fun accept(project: Project) = isAndroid(project)

    fun compileSdkVersion(project: Project) = configurationFor(project)?.compileSdkVersion

    fun buildToolsVersion(project: Project): String {
        val version = configurationFor(project)?.buildToolsVersion
        if (OperatingSystem.current().isWindows() && version == "21.1.2")
            return "build-tools-$version"
        else
            return version as String
    }

    inline fun androidHome(project: Project?) = AndroidFiles.androidHome(project, configurationFor(project)!!)

    fun androidJar(project: Project): Path =
            Paths.get(androidHome(project), "platforms", "android-${compileSdkVersion(project)}", "android.jar")

    private fun aapt(project: Project) = "${androidHome(project)}/build-tools/${buildToolsVersion(project)}/aapt"

    private fun adb(project: Project) = "${androidHome(project)}/platform-tools/adb"

    private fun temporaryApk(project: Project, flavor: String)
            = KFiles.joinFileAndMakeDir(AndroidFiles.intermediates(project), "res", "resources$flavor.ap_")

    private fun apk(project: Project, flavor: String)
            = KFiles.joinFileAndMakeDir(project.buildDirectory, "outputs", "apk", "${project.name}$flavor.apk")

    @Task(name = "generateR", description = "Generate the R.java file",
            runBefore = arrayOf("compile"), runAfter = arrayOf("clean"))
    fun taskGenerateRFile(project: Project): TaskResult {

        val intermediates = AndroidFiles.intermediates(project)
        val resDir = "temporaryBogusResDir"
        val aarDependencies= explodeAarFiles(project, intermediates, File(resDir))
        AndroidBuild().run(project, context.variant, configurationFor(project)!!, aarDependencies)
//        merger.merge(project, context)

        val notUsed = ""
        val generated = AndroidFiles.generated(project)
        val success = generateR(project, generated, aapt(project))
        return TaskResult(success)
    }

    /**
     * aapt returns 0 even if it fails, so in order to detect whether it failed, we are checking
     * if its error stream contains anything.
     */
    inner class AaptCommand(project: Project, aapt: String, val aaptCommand: String, cwd: File = File("."),
            args: List<String>)
        : AndroidCommand(project, androidHome(project), aapt,
                directory = cwd,
                useErrorStreamAsErrorIndicator = true,
                args = arrayListOf(aaptCommand) + args)

    private fun generateR(project: Project, generated: String, aapt: String) : Boolean {
        val compileSdkVersion = compileSdkVersion(project)
        val androidJar = Paths.get(androidHome(project), "platforms", "android-$compileSdkVersion", "android.jar")
        val applicationId = configurationFor(project)?.applicationId!!
        val intermediates = AndroidFiles.intermediates(project)

//        AaptCommand(project, aapt, "crunch").call(listOf(
//                "-v",
//                "-C", mergedResources(project, context.variant),
//                "-S", crunchedPngDir
//        ))

        val variantDir = context.variant.toIntermediateDir()

        val rDirectory = KFiles.joinAndMakeDir(generated, "source", "r", variantDir).toString()
        val result = AaptCommand(project, aapt, "package", args = listOf(
                "-f",
                "--no-crunch",
                "-I", androidJar.toString(),
                "-M", AndroidFiles.mergedManifest(project, context.variant),
                "-S", AndroidFiles.mergedResources(project, context.variant),
                // where to find more assets
                "-A", KFiles.joinAndMakeDir(intermediates, "assets", variantDir),
                "-m", // create directory
                // where all gets generated
                "-J", rDirectory,
                "-F", temporaryApk(project, context.variant.shortArchiveName),
                "--debug-mode",
                "-0", "apk",
                "--auto-add-overlay",
                "--custom-package", applicationId
         //       "--output-text-symbols", KFiles.joinAndMakeDir(intermediates(project).toString(), "symbol", flavor)
        ))

        val rOutputDirectory = KFiles.joinDir(rDirectory, applicationId.replace(".", File.separator))
        val generatedBuildDir = compile(project, rOutputDirectory)
        project.compileDependencies.add(FileDependency(generatedBuildDir.path))
        return result == 0
    }

    /**
     * Extract all the .aar files found in the dependencies and add the android.jar to classpathEntries,
     * which will be added to the classpath at compile time
     */
    private fun explodeAarFiles(project: Project, outputDir: String, resDir: File) : List<File> {
        val result = arrayListOf<File>()
        project.compileDependencies.filter {
            it.jarFile.get().name.endsWith(".aar")
        }.forEach {
            val mavenId = MavenId(it.id)
            val destDir = Paths.get(outputDir, "exploded-aar", mavenId.groupId,
                    mavenId.artifactId, mavenId.version)
                    .toFile()
            log(2, "Exploding ${it.jarFile.get()} to $destDir")
            JarUtils.extractJarFile(it.jarFile.get(), destDir)
            val classesJar = Paths.get(destDir.absolutePath, "classes.jar")

            // Add the classses.jar of this .aar to the classpath entries (which are returned via IClasspathContributor)
            classpathEntries.put(project.name, FileDependency(classesJar.toFile().absolutePath))
            // Also add all the jar files found in the libs/ directory
            File(destDir, "libs").let { libsDir ->
                if (libsDir.exists()) {
                    libsDir.listFiles().filter { it.name.endsWith(".jar")}.forEach {
                        classpathEntries.put(project.name, FileDependency(it.absolutePath))
                    }
                }
            }

            // Copy all the resources from this aar into the same intermediate directory
            log(2, "Copying the resources to $resDir")
            result.add(destDir)
            KFiles.copyRecursively(destDir.resolve("res"), resDir, deleteFirst = false)
        }
        return result
    }

    private fun compile(project: Project, rDirectory: String): File {
        val sourceFiles = arrayListOf(Paths.get(rDirectory, "R.java").toFile().path)
        val buildDir = File(AndroidFiles.generated(project), "classes")
        // Using a directory of "." since the project.directory is already present in buildDir
        val cai = CompilerActionInfo(".", listOf(), sourceFiles, buildDir, listOf(
                "-source", "1.6", "-target", "1.6"))
        javaCompiler.compile(project, context, cai)
        return buildDir
    }

    /**
     * Implements ICompilerFlagContributor
     * Make sure we compile and generate 1.6 sources unless the build file defined those (which can
     * happen if the developer is using RetroLambda for example).
     */
    override fun flagsFor(project: Project, currentFlags: List<String>) : List<String> {
        if (isAndroid(project)) {
            val result = arrayListOf<String>()
            var foundSource = false
            var foundTarget = false
            var noWarn = false
            var i = 0
            while (i < currentFlags.size) {
                with(currentFlags[i]) {
                    if (this == "-source") {
                        result.add(this)
                        result.add(currentFlags[i + 1])
                        i++
                        foundSource = true
                    } else if (this == "-target") {
                        result.add(this)
                        result.add(currentFlags[i + 1])
                        i++
                        foundTarget = true
                    } else {
                        result.add(this)
                    }
                }
                i++
            }
            if (! foundSource) {
                result.add("-source")
                result.add("1.6")
                noWarn = true
            }
            if (! foundTarget) {
                result.add("-target")
                result.add("1.6")
                noWarn = true
            }
            if (noWarn) {
                result.add("-nowarn")
            }
            return result
        } else {
            return emptyList()
        }
    }

    @Task(name = "proguard", description = "Run Proguard, if enabled", runBefore = arrayOf(TASK_GENERATE_DEX),
            runAfter = arrayOf("compile"))
    fun taskProguard(project: Project): TaskResult {
        val config = configurationFor(project)
        if (config != null) {
            val buildType = context.variant.buildType
            if (buildType.minifyEnabled) {
                log(1, "minifyEnabled is true, running Proguard")
                val classesDir = project.classesDir(context)
                val proguardHome = KFiles.joinDir(androidHome(project), "tools", "proguard")
                val proguardCommand = KFiles.joinDir(proguardHome, "bin", "proguard.sh")
            }
        }
        return TaskResult()
    }

    @Task(name = TASK_GENERATE_DEX, description = "Generate the dex file", runBefore = arrayOf("assemble"),
            runAfter = arrayOf("compile"))
    fun taskGenerateDex(project: Project): TaskResult {
        //
        // Call dx to generate classes.dex
        //
        val buildToolsDir = buildToolsVersion(project)
        val dx = "${androidHome(project)}/build-tools/$buildToolsDir/dx" +
                if (OperatingSystem.current().isWindows()) ".bat" else ""
        val classesDexDir = KFiles.joinDir(AndroidFiles.intermediates(project), "dex",
                context.variant.toIntermediateDir())
        File(classesDexDir).mkdirs()
        val classesDex = "classes.dex"
        val outClassesDex = KFiles.joinDir(classesDexDir, classesDex)

        // java.exe -Xmx1024M -Dfile.encoding=windows-1252 -Duser.country=US -Duser.language=en -Duser.variant -cp D:\android\adt-bundle-windows-x86_64-20140321\sdk\build-tools\23.0.1\lib\dx.jar com.android.dx.command.Main --dex --verbose --num-threads=4 --output C:\Users\cbeust\android\android_hello_world\app\build\intermediates\dex\pro\debug C:\Users\cbeust\android\android_hello_world\app\build\intermediates\classes\pro\debug

        val javaExecutable = JavaInfo.create(File(SystemProperties.javaBase)).javaExecutable!!
        RunCommand(javaExecutable.absolutePath).run(listOf(
                "-cp", KFiles.joinDir(androidHome(project), "build-tools", buildToolsVersion(project), "lib", "dx.jar"),
                "com.android.dx.command.Main",
                "--dex", "--verbose", "--num-threads=4",
                "--output", outClassesDex,
                   //KFiles.joinDir(intermediates(project), "dex", context.variant.toIntermediateDir()),
                project.classesDir(context)
        ))

        //
        // Add classes.dex to existing .ap_
        // Because aapt doesn't handle directory moving, we need to cd to classes.dex's directory so
        // that classes.dex ends up in the root directory of the .ap_.
        //
        AaptCommand(project, aapt(project), "add").apply {
            directory = File(outClassesDex).parentFile
        }.call(listOf("-v", KFiles.joinDir(
                File(temporaryApk(project, context.variant.shortArchiveName)).absolutePath), classesDex))

        return TaskResult()
    }

    private val DEFAULT_DEBUG_SIGNING_CONFIG = SigningConfig(
            SigningConfig.DEFAULT_STORE_FILE,
            SigningConfig.DEFAULT_STORE_PASSWORD,
            SigningConfig.DEFAULT_KEY_ALIAS,
            SigningConfig.DEFAULT_KEY_PASSWORD)

    /**
     * Sign the apk
     * Mac:
     * jarsigner -keystore ~/.android/debug.keystore -storepass android -keypass android -signedjar a.apk a.ap_
     * androiddebugkey
     */
    @Task(name = TASK_SIGN_APK, description = "Sign the apk file", runAfter = arrayOf(TASK_GENERATE_DEX),
            runBefore = arrayOf("assemble"))
    fun taskSignApk(project: Project): TaskResult {
        val apk = apk(project, context.variant.shortArchiveName)
        val temporaryApk = temporaryApk(project, context.variant.shortArchiveName)
        val buildType = context.variant.buildType.name

        val config = configurationFor(project)
        var signingConfig = config!!.signingConfigs[buildType]

        if (signingConfig == null && buildType != "debug") {
            warn("No signingConfig found for product type \"$buildType\", using the \"debug\" signConfig")
        }

        signingConfig = DEFAULT_DEBUG_SIGNING_CONFIG

        val success = RunCommand("jarsigner").apply {
//            useInputStreamAsErrorIndicator = true
        }.run(listOf(
                "-keystore", signingConfig.storeFile,
                "-storepass", signingConfig.storePassword,
                "-keypass", signingConfig.keyPassword,
                "-signedjar", apk,
                temporaryApk,
                signingConfig.keyAlias
            ))
            log(1, "Created $apk")

        return TaskResult(success == 0)
    }

    @Task(name = TASK_INSTALL, description = "Install the apk file", runAfter = arrayOf(TASK_GENERATE_DEX, "assemble"))
    fun taskInstall(project: Project): TaskResult {

        /**
         * adb has weird ways of signaling errors, that's the best I've found so far.
         */
        class AdbInstall : RunCommand(adb(project)) {
            override fun isSuccess(isSuccess: Boolean, input: List<String>, error: List<String>)
                = input.filter { it.contains("Success")}.size > 0
        }

        val apk = apk(project, context.variant.shortArchiveName)
        val result = AdbInstall().useErrorStreamAsErrorIndicator(true).run(
                args = listOf("install", "-r", apk))
        log(1, "Installed $apk")
        return TaskResult(result == 0)
    }

    private val classpathEntries = HashMultimap.create<String, IClasspathDependency>()

    // IClasspathContributor
    override fun entriesFor(project: Project?): Collection<IClasspathDependency> {
        return if (project != null) {
            classpathEntries.get(project.name) ?: emptyList()
        } else {
            emptyList()
        }
    }

    // IRepoContributor
    override fun reposFor(project: Project?): List<HostConfig> {
        val config = configurationFor(project)
        var home = AndroidFiles.androidHomeNoThrows(project, config)

        return if (home != null) {
            val path = Paths.get(KFiles.joinDir(home, "extras", "android", "m2repository"))
            listOf(HostConfig(path.toUri().toString()))
        } else {
            emptyList()
        }
    }

    // IBuildDirectoryInterceptor
    override fun intercept(project: Project, context: KobaltContext, buildDirectory: String): String {
        if (isAndroid(project)) {
            val result = KFiles.joinDir(AndroidFiles.intermediates(project), "classes",
                    context.variant.toIntermediateDir())
            return result
        } else {
            return buildDirectory
        }
    }

    // ICompilerInterceptor
    override fun intercept(project: Project, context: KobaltContext, actionInfo: CompilerActionInfo)
            : CompilerActionInfo {
        val result: CompilerActionInfo =
            if (isAndroid(project)) {
                val newOutputDir = KFiles.joinDir("kobaltBuild", "intermediates", "classes",
                        context.variant.toIntermediateDir())
                actionInfo.copy(outputDir = File(newOutputDir))
            } else {
                actionInfo
            }
        return result
    }

    // IRunContributor
    override fun affinity(project: Project, context: KobaltContext): Int {
        val manifest = AndroidFiles.manifest(project, context)
        return if (File(manifest).exists()) IAffinity.DEFAULT_POSITIVE_AFFINITY else 0
    }

    override fun run(project: Project, context: KobaltContext, classpath: List<IClasspathDependency>): TaskResult {
        val manifest = AndroidFiles.manifest(project, context)
        FileInputStream(File(manifest)).use { ins ->
            // adb shell am start -n com.package.name/com.package.name.ActivityName
            val manifest = AndroidManifest(ins)
            RunCommand(adb(project)).useErrorStreamAsErrorIndicator(false).run(args = listOf(
                    "shell", "am", "start", "-n", manifest.pkg + "/" + manifest.mainActivity))
            return TaskResult()
        }
    }

    /**
     * Automatically add the "aar" packaging for support libraries.
     */
    // IClasspathInterceptor
    override fun intercept(dependencies: List<IClasspathDependency>): List<IClasspathDependency> {
        val result = arrayListOf<IClasspathDependency>()
        dependencies.forEach {
            if (it is MavenDependency && it.groupId == "com.android.support") {
                val id = MavenId.create(it.groupId, it.artifactId, "aar", it.version)
                result.add(MavenDependency.create(id, executors.miscExecutor))
            } else {
                result.add(it)
            }
        }
        return result
    }

    private val extraSourceDirectories = arrayListOf<File>()

    // ISourceDirectoryContributor
    override fun sourceDirectoriesFor(project: Project, context: KobaltContext): List<File> = extraSourceDirectories

    // IBuildConfigFieldContributor
    override fun fieldsFor(project: Project, context: KobaltContext): List<BuildConfigField> {
        val result = arrayListOf<BuildConfigField>()
        configurationFor(project)?.let { config ->
            result.add(BuildConfigField("String", "VERSION_NAME", "\"${config.versionName}\""))
            result.add(BuildConfigField("int", "VERSION_CODE", "${config.versionCode}"))
        }
        return result
    }

    //ITaskContributor
    override fun tasksFor(context: KobaltContext): List<DynamicTask> = taskContributor.dynamicTasks
}

class AndroidConfig(val project: Project,
        var compileSdkVersion : String? = null,
        var buildToolsVersion: String? = null,
        var minSdkVersion: String? = null,
        var versionCode: Int? = null,
        var versionName: String? = null,
        var targetSdkVersion: String? = null,
        var applicationId: String? = null,
        val androidHome: String? = null) {

    val signingConfigs = hashMapOf<String, SigningConfig>()

    fun addSigningConfig(name: String, project: Project, signingConfig: SigningConfig) {
        signingConfigs.put(name, signingConfig)
    }
}

@Directive
fun Project.android(init: AndroidConfig.() -> Unit) : AndroidConfig = let { project ->
    return AndroidConfig(project).apply {
        init()
        (Kobalt.findPlugin(AndroidPlugin.PLUGIN_NAME) as AndroidPlugin).addConfiguration(project, this)
    }
}

class SigningConfig(var storeFile: String = SigningConfig.DEFAULT_STORE_FILE,
        var storePassword: String = SigningConfig.DEFAULT_STORE_PASSWORD,
        var keyAlias: String = SigningConfig.DEFAULT_KEY_ALIAS,
        var keyPassword: String = SigningConfig.DEFAULT_KEY_ALIAS) {

    companion object {
        val DEFAULT_STORE_FILE = homeDir(".android", "debug.keystore")
        val DEFAULT_STORE_PASSWORD = "android"
        val DEFAULT_KEY_ALIAS = "androiddebugkey"
        val DEFAULT_KEY_PASSWORD = "android"
    }
}

@Directive
fun AndroidConfig.signingConfig(name: String, init: SigningConfig.() -> Unit) : SigningConfig = let { androidConfig ->
    SigningConfig().apply {
        init()
        androidConfig.addSigningConfig(name, project, this)
    }
}


