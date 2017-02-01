import sbt._

object Dependencies {

  val Version = "0.3-SNAPSHOT"
  val scalaVersion = "2.11.8"
  val akkaVersion = "2.4.16"

  val pkg = "org.tmt" %% "pkg" % Version
  val cs = "org.tmt" %% "cs" % Version
  val ccs = "org.tmt" %% "ccs" % Version
  val ts = "org.tmt" %% "ts" % Version
  val alarms = "org.tmt" %% "alarms" % Version
  val events = "org.tmt" %% "events" % Version
  val containerCmd = "org.tmt" %% "containercmd" % Version
  val seqSupport = "org.tmt" %% "seqsupport" % Version
  val javacsw = "org.tmt" %% "javacsw" % Version

// Test dependencies
  val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion // ApacheV2
  val scalaTest = "org.scalatest" %% "scalatest" % "2.2.6" // ApacheV2
  val junit = "com.novocode" % "junit-interface" % "0.11" // Two-clause BSD-style license
  val junitInterface = "com.novocode" % "junit-interface" % "0.11"
  val specs2 = "org.specs2" %% "specs2-core" % "3.7" // MIT-style
  val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.7.0"
}

