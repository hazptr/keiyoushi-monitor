// Gradle init script used by ext-run.sh (never committed to the repo).
// Adds `extRunClasspath` to the project given by -PextRun.project=:src:<lang>:<name>: it compiles the
// debug variant's Kotlin/Java and writes the JVM class dirs/jars the extension needs at runtime
// (its own classes + :core + lib/lib-multisrc project deps) to build/ext-run/classpath.txt.
abstract class ExtRunClasspath : DefaultTask() {
    @get:InputFiles
    abstract val entries: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText(entries.files.filter { it.exists() }.joinToString("\n") { it.absolutePath } + "\n")
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
            tasks.register<ExtRunClasspath>("extRunClasspath") {
                dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac", "generateExtensionManifest")
                entries.from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
                entries.from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
                entries.from(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
                entries.from(deps)
                output.set(layout.buildDirectory.file("ext-run/classpath.txt"))
            }
        }
    }
}
