package scalajsbundler

import org.scalajs.core.ir.Trees.JSNativeLoadSpec
import org.scalajs.core.tools.io.VirtualScalaJSIRFile
import org.scalajs.core.tools.linker.ClearableLinker
import org.scalajs.core.tools.linker.backend.{BasicLinkerBackend, LinkerBackend, OutputMode}
import org.scalajs.sbtplugin.Loggers
import org.scalajs.sbtplugin.ScalaJSPlugin.AutoImport._
import sbt._

import JS.syntax._

/**
  * Faster workflow when a developer changes the source code and then reloads the application.
  *
  * It pre-bundles the dependencies once and then recompiles just the part of the application
  * that changed.
  *
  * For this to work, the pre-bundled dependencies are exposed to the global namespace and a fake `require`
  * implementation is also provided so that the output of Scala.js, which is supposed to be executed by
  * a CommonJS compatible environment, can be executed by a web browser.
  */
object ReloadWorkflow {

  /**
    * @return A file that contains the concatenation of the dependencies bundle and the output of the Scala.js compilation
    * @param bundledDependencies Pre-bundled dependencies
    * @param loader `require` implementation
    * @param launcher Entry point launcher
    * @param sjsOutput Output of Scala.js
    * @param targetDir Target directory (where to write the bundle)
    */
  def writeFakeBundle(
    bundledDependencies: File,
    loader: File,
    launcher: File,
    sjsOutput: File,
    targetDir: File
  ): File = {
    val moduleName = sjsOutput.name.stripSuffix(".js")
    val bundle = targetDir / s"$moduleName-bundle.js" // Because scalajs.webpack.config.js defines the output as "[name]-bundle.js"
    IO.copyFile(bundledDependencies, bundle)
    IO.append(bundle, "\n")
    IO.append(bundle, IO.readBytes(loader)) // shims `require`
    IO.append(bundle, ";\n")
    IO.append(bundle, IO.readBytes(sjsOutput))
    IO.append(bundle, "\n")
    IO.append(bundle, IO.readBytes(launcher))
    bundle
  }

  /**
    * @return The written loader file (faking a `require` implementation)
    * @param loader File to write the loader to
    * @param logger Logger
    */
  def writeLoader(loader: File, logger: Logger): Unit = {
    logger.info("Writing the module loader file")
    val window = JS.ref("window")
    val depsLoaderContent =
      JS.block(
        (window `.` "require") := JS.fun(name => window.bracket((JS.str(modulePrefix) `.` "concat")(name))),
        (window `.` "exports") := JS.obj()
      )
    IO.write(loader, depsLoaderContent.show)
    ()
  }

  /**
    * @return The written launcher file (calling the application entry point)
    * @param mainClass Application entry point
    * @param launcher File to write the launcher to
    * @param logger Logger
    */
  def writeLauncher(mainClass: String, launcher: File, logger: Logger): Unit = {
    logger.info("Writing the launcher file")
    val window = JS.ref("window")
    val launcherContent = Launcher.callEntryPoint(mainClass, window `.` "exports")
    IO.write(launcher, launcherContent.show)
    ()
  }

  /**
    * @return The imported dependencies of the Scala.js project, bundled into a single file, and exported to the
    *         global namespace
    * @param imports Imported module names
    * @param workingDir Directory where node_modules are present
    * @param entryPoint File to write the bundle entry point to
    * @param bundleFile File to write the bundle to
    * @param logger Logger
    */
  def bundleDependencies(
    imports: Seq[String],
    workingDir: File,
    entryPoint: File,
    bundleFile: File,
    logger: Logger
  ): Unit = {
    logger.info("Pre-bundling dependencies")

    val depsFileContent =
      JS.block(
        imports.map { moduleName =>
          JS.ref("global").bracket(s"$modulePrefix$moduleName") := JS.ref("require")(JS.str(moduleName))
        }: _*
      )
    IO.write(entryPoint, depsFileContent.show)

    val webpackBin = workingDir / "node_modules" / "webpack" / "bin" / "webpack"
    Commands.run(
      s"node ${webpackBin.absolutePath} ${entryPoint.absolutePath} ${bundleFile.absolutePath}",
      workingDir,
      logger
    )

    ()
  }

  /**
    * @return The list of ES modules imported by a Scala.js project
    * @param linker Scala.js linker
    * @param irFiles Scala.js IR files
    * @param outputMode Scala.js output mode
    * @param emitSourceMaps Whether emitSourceMaps is enabled
    * @param logger Logger
    */
  def findImportedModules(
    linker: ClearableLinker,
    irFiles: Seq[VirtualScalaJSIRFile],
    outputMode: OutputMode,
    emitSourceMaps: Boolean,
    logger: Logger
  ): List[String] = {
    val semantics = linker.semantics
    val symbolRequirements =
      new BasicLinkerBackend(semantics, outputMode, ModuleKind.CommonJSModule, emitSourceMaps, LinkerBackend.Config())
        .symbolRequirements
    val linkingUnit =
      linker.linkUnit(irFiles, symbolRequirements, Loggers.sbtLogger2ToolsLogger(logger))
    linkingUnit.classDefs.flatMap(_.jsNativeLoadSpec).collect {
      case JSNativeLoadSpec.Import(module, _) => module
    }.distinct
  }

  // Prefix to use to export/import pre-bundled modules to/from the global namespace
  val modulePrefix = "__scalajsbundler__"

}
