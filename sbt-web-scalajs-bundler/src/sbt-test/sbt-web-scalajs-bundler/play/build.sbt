val client =
  project.in(file("client"))
    .enablePlugins(ScalaJSBundlerPlugin, ScalaJSWeb)
    .settings(
      scalaVersion := "2.11.8",
      libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.1",
      npmDependencies in Compile ++= Seq(
        "snabbdom" -> "0.5.3",
        "font-awesome" -> "4.7.0"
      )
    )

val server =
  project.in(file("server"))
    .enablePlugins(PlayScala, WebScalaJSBundlerPlugin)
    .disablePlugins(PlayLayoutPlugin)
    .settings(
      scalaVersion := "2.11.8",
      libraryDependencies += "com.typesafe.play" %% "twirl-api" % "1.2.0",
      libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test,
      scalaJSProjects := Seq(client),
      pipelineStages in Assets := Seq(scalaJSPipeline),
      pipelineStages := Seq(digest, gzip),
      npmAssets ++= NpmAssets.ofProject(client) { modules => (modules / "font-awesome").*** }.value
    )

val play =
  project.in(file("."))
    .aggregate(client, server)
