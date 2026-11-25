// Gradle init script used by ext-run.sh (never committed to the repo).
// Adds `extRunClasspath` to the project given by -PextRun.project=:src:<lang>:<name>: it compiles the
// debug variant's Kotlin/Java and writes the JVM class dirs/jars the extension needs at runtime
// (its own classes + :core + lib/lib-multisrc project deps) to build/ext-run/classpath.txt.
// lib-classpath.txt: the extensions-lib jar it compiles against, which ext-run.sh puts after the runtime, so API
// classes the runtime doesn't implement yet (a proposed API on a fork's lib) load from the lib itself.
abstract class ExtRunClasspath : DefaultTask() {
    @get:InputFiles
    abstract val entries: ConfigurableFileCollection

    @get:InputFiles
    abstract val libEntries: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:OutputFile
    abstract val libOutput: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText(entries.files.filter { it.exists() }.joinToString("\n") { it.absolutePath } + "\n")
        libOutput.get().asFile.writeText(libEntries.files.filter { it.exists() }.joinToString("\n") { it.absolutePath } + "\n")
    }
}

val extRunProject = gradle.startParameter.projectProperties["extRun.project"]

allprojects {
    // ALL: every extension at once (ext-run.sh --prebuild), one gradle run instead of one per extension.
    if (path == extRunProject || (extRunProject == "ALL" && path.startsWith(":src:"))) {
        pluginManager.withPlugin("com.android.application") {
            val artifactType = Attribute.of("artifactType", String::class.java)
            // AGP creates the variant configurations late, so resolve them lazily.
            val deps = provider {
                configurations.getByName("debugRuntimeClasspath").incoming
                    .artifactView { attributes.attribute(artifactType, "android-classes-jar") }.files
            }
            val libJars = provider {
                configurations.getByName("debugCompileClasspath").incoming
                    .artifactView { attributes.attribute(artifactType, "android-classes-jar") }.artifacts
                    .filter { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.module == "extensions-lib" }
                    .map { it.file }
            }
            tasks.register<ExtRunClasspath>("extRunClasspath") {
                dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac", "generateExtensionManifest")
                entries.from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
                entries.from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
                entries.from(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
                entries.from(deps)
                libEntries.from(libJars)
                output.set(layout.buildDirectory.file("ext-run/classpath.txt"))
                libOutput.set(layout.buildDirectory.file("ext-run/lib-classpath.txt"))
            }
        }
    }
}
