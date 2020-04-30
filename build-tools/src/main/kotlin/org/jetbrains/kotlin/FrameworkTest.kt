package org.jetbrains.kotlin

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin

import org.jetbrains.kotlin.konan.target.*
import java.io.File

import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test task for -produce framework testing. Requires a framework to be built by the Konan plugin
 * with konanArtifacts { framework(frameworkName, targets: [ testTarget] ) } and a dependency set
 * according to a pattern "compileKonan${frameworkName}".
 *
 * @property swiftSources  Swift-language test sources that use a given framework
 * @property testName test name
 * @property frameworkNames names of frameworks
 */
open class FrameworkTest : DefaultTask(), KonanTestExecutable {
    @Input
    lateinit var swiftSources: List<String>

    @Input
    lateinit var testName: String

    @Input
    lateinit var frameworks: MutableList<Framework>

    @Input
    var fullBitcode: Boolean = false

    @Input
    var codesign: Boolean = true

    val testOutput: String = project.testOutputFramework

    /**
     * Framework description.
     * @param name is the framework name,
     * @param sources framework sources,
     * @param bitcode bitcode embedding in the framework,
     * @param artifact the name of the resulting artifact,
     * @param library library dependency name,
     * @param opts additional options for the compiler.
     */
    data class Framework(
            val name: String,
            val sources: List<String>,
            val bitcode: Boolean = false,
            val artifact: String = name,
            val library: String? = null,
            val opts: List<String> = emptyList()
    )

    @JvmOverloads
    fun createFramework(name: String,
                        sources: List<String>,
                        bitcode: Boolean = false,
                        artifact: String = name,
                        library: String? = null,
                        opts: List<String> = emptyList()
    ) = Framework(
            name,
            sources.toFiles(Language.Kotlin).map { it.path },
            bitcode, artifact, library, opts
    ).also {
        if (!::frameworks.isInitialized) {
            frameworks = mutableListOf(it)
        } else {
            frameworks.add(it)
        }
    }

    enum class Language(val extension: String) {
        Kotlin(".kt"), ObjC(".m"), Swift(".swift")
    }

    fun Language.filesFrom(dir: String): FileTree = project.fileTree(dir) {
        it.include("**/*${this.extension}")
    }

    fun List<String>.toFiles(language: Language): List<File> =
            this.map { language.filesFrom(it) }
                    .flatMap { it.files }

    override val executable: String
        get() {
            check(::testName.isInitialized) { "Test name should be set" }
            return Paths.get(testOutput, testName, "swiftTestExecutable").toString()
        }

    override var doBeforeRun: Action<in Task>? = null

    override var doBeforeBuild: Action<in Task>? = null

    override val buildTasks: List<Task>
        get() = frameworks.map { project.tasks.getByName("compileKonan${it.name}") }

    @Suppress("UnstableApiUsage")
    override fun configure(config: Closure<*>): Task {
        super.configure(config)
        // set crossdist build dependency if custom konan.home wasn't set
        this.dependsOnDist()

        // Set Gradle properties for the better navigation
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Kotlin/Native test infrastructure task"

        check(::testName.isInitialized) { "Test name should be set" }
        check(::frameworks.isInitialized) { "Frameworks should be set" }

        // Build test executable as a first action of the task before executing the test
        // TODO: check why is this action not in the run()?
        this.doFirst { buildTestExecutable() }
        return this
    }

    private fun buildTestExecutable() {
        val frameworkParentDirPath = "$testOutput/$testName/${project.testTarget.name}"
        frameworks.forEach { framework ->
            val frameworkArtifact = framework.artifact
            val frameworkPath = "$frameworkParentDirPath/$frameworkArtifact.framework"
            val frameworkBinaryPath = "$frameworkPath/$frameworkArtifact"
            validateBitcodeEmbedding(frameworkBinaryPath)
            if (codesign) codesign(project, frameworkPath)
        }

        // create a test provider and get main entry point
        val provider = Paths.get(testOutput, testName, "provider.swift")
        FileWriter(provider.toFile()).use { writer ->
            val providers = swiftSources.toFiles(Language.Swift)
                    .map { it.name.toString().removeSuffix(".swift").capitalize() }
                    .map { "${it}Tests" }

            writer.write("""
                |// THIS IS AUTOGENERATED FILE
                |// This method is invoked by the main routine to get a list of tests
                |func registerProviders() {
                |    ${providers.joinToString("\n    ") { "$it()" }}
                |}
                """.trimMargin())
        }
        val testHome = project.file("framework").toPath()
        val swiftMain = Paths.get(testHome.toString(), "main.swift").toString()

        // Compile swift sources
        val sources = swiftSources.toFiles(Language.Swift)
                .map { it.path } + listOf(provider.toString(), swiftMain)
        val options = listOf(
                "-g",
                "-Xlinker", "-rpath", "-Xlinker", "@executable_path/Frameworks",
                "-Xlinker", "-rpath", "-Xlinker", frameworkParentDirPath,
                "-F", frameworkParentDirPath,
                "-Xcc", "-Werror" // To fail compilation on warnings in framework header.
        )
        compileSwift(project, project.testTarget, sources, options, Paths.get(executable), fullBitcode)
    }

    @TaskAction
    fun run() {
        doBeforeRun?.execute(this)
        runTest(executorService = project.executor, testExecutable = Paths.get(executable))
    }

    /**
     * Returns path to directory that contains `libswiftCore.dylib` for the current
     * test target.
     */
    private fun getSwiftLibsPathForTestTarget(): String {
        val target = project.testTarget
        val platform = project.platformManager.platform(target)
        val configs = platform.configurables as AppleConfigurables
        val swiftPlatform = when (target) {
            KonanTarget.IOS_X64 -> "iphonesimulator"
            KonanTarget.IOS_ARM32, KonanTarget.IOS_ARM64 -> "iphoneos"
            KonanTarget.TVOS_X64 -> "appletvsimulator"
            KonanTarget.TVOS_ARM64 -> "appletvos"
            KonanTarget.MACOS_X64 -> "macosx"
            KonanTarget.WATCHOS_ARM64 -> "watchos"
            KonanTarget.WATCHOS_X64, KonanTarget.WATCHOS_X86 -> "watchsimulator"
            else -> throw IllegalStateException("Test target $target is not supported")
        }
        val simulatorPath = when (target) {
            KonanTarget.TVOS_X64,
            KonanTarget.IOS_X64,
            KonanTarget.WATCHOS_X86,
            KonanTarget.WATCHOS_X64 -> Xcode.current.getLatestSimulatorRuntimeFor(target, configs.osVersionMin)?.bundlePath?.let {
                "$it/Contents/Resources/RuntimeRoot/usr/lib/swift"
            }
            else -> null
        }
        // Use default path from toolchain if we cannot get `bundlePath` for target.
        // It may be the case for simulators if Xcode/macOS is old.
        return simulatorPath ?: configs.absoluteTargetToolchain + "/usr/lib/swift-5.0/$swiftPlatform"
    }

    private fun buildEnvironment(): Map<String, String> {
        val target = project.testTarget
        // Hopefully, lexicographical comparison will work.
        val newMacos = System.getProperty("os.version").compareTo("10.14.4") >= 0
        val dyldLibraryPathKey = when (target) {
            KonanTarget.IOS_X64,
            KonanTarget.WATCHOS_X86,
            KonanTarget.WATCHOS_X64,
            KonanTarget.TVOS_X64 -> "SIMCTL_CHILD_DYLD_LIBRARY_PATH"
            else -> "DYLD_LIBRARY_PATH"
        }
        return if (newMacos && target == KonanTarget.MACOS_X64) emptyMap() else mapOf(
                dyldLibraryPathKey to getSwiftLibsPathForTestTarget()
        )
    }

    private fun runTest(executorService: ExecutorService, testExecutable: Path, args: List<String> = emptyList()) {
        val (stdOut, stdErr, exitCode) = runProcess(
                executor = { executorService.add(Action {
                    it.environment = buildEnvironment()
                    it.workingDir = Paths.get(testOutput).toFile()
                }).execute(it) },
                executable = testExecutable.toString(),
                args = args)

        val testExecName = testExecutable.fileName
        println("""
            |$testExecName
            |stdout: $stdOut
            |stderr: $stdErr
            """.trimMargin())
        check(exitCode == 0) { "Execution of $testExecName failed with exit code: $exitCode " }
    }

    private fun validateBitcodeEmbedding(frameworkBinary: String) {
        // Check only the full bitcode embedding for now.
        if (!fullBitcode) {
            return
        }
        val testTarget = project.testTarget
        val configurables = project.platformManager.platform(testTarget).configurables as AppleConfigurables

        val bitcodeBuildTool = "${configurables.absoluteAdditionalToolsDir}/bin/bitcode-build-tool"
        val toolPath = "${configurables.absoluteTargetToolchain}/usr/bin/"
        val sdk = when (testTarget) {
            KonanTarget.IOS_X64,
            KonanTarget.TVOS_X64,
            KonanTarget.WATCHOS_X86,
            KonanTarget.WATCHOS_X64 -> return // bitcode-build-tool doesn't support simulators.
            KonanTarget.IOS_ARM64,
            KonanTarget.IOS_ARM32 -> Xcode.current.iphoneosSdk
            KonanTarget.MACOS_X64 -> Xcode.current.macosxSdk
            KonanTarget.TVOS_ARM64 -> Xcode.current.appletvosSdk
            KonanTarget.WATCHOS_ARM32,
            KonanTarget.WATCHOS_ARM64 -> Xcode.current.watchosSdk
            else -> error("Cannot validate bitcode for test target $testTarget")
        }

        val python3 = listOf("/usr/bin/python3", "/usr/local/bin/python3")
                .map { Paths.get(it) }.firstOrNull { Files.exists(it) }
                ?: error("Can't find python3")

        runTest(executorService = localExecutorService(project), testExecutable = python3,
                args = listOf("-B", bitcodeBuildTool, "--sdk", sdk, "-v", "-t", toolPath, frameworkBinary))
    }
}