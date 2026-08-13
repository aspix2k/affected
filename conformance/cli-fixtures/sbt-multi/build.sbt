ThisBuild / scalaVersion := "3.3.6"

lazy val root = (project in file(".")).aggregate(alpha, beta)
lazy val alpha = project
lazy val beta = project

ThisBuild / libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test
ThisBuild / testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v")
