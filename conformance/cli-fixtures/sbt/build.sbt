ThisBuild / scalaVersion := "3.3.6"
libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test
testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v")
