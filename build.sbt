ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.14"

lazy val pekkoVersion = "1.1.3"
lazy val pekkoHttpVersion = "1.1.0"
lazy val scalaTestVersion = "3.2.19"

lazy val root = (project in file("."))
  .settings(
    name := "ConversionService",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    )
  )
