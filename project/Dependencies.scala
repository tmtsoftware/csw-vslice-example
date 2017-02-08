import sbt._

object Dependencies {

  val Version = "0.4-SNAPSHOT"
  val scalaVersion = "2.12.1"
  val akkaVersion = "2.4.16"

  val pkg = "org.tmt" %% "pkg" % Version
  val cs = "org.tmt" %% "cs" % Version
  val ccs = "org.tmt" %% "ccs" % Version
  val log = "org.tmt" %% "log" % Version
  val ts = "org.tmt" %% "ts" % Version
  val alarms = "org.tmt" %% "alarms" % Version
  val events = "org.tmt" %% "events" % Version
  val containerCmd = "org.tmt" %% "containercmd" % Version
  val seqSupport = "org.tmt" %% "seqsupport" % Version
  val javacsw = "org.tmt" %% "javacsw" % Version

  val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"

  // -- Test dependencies --
  // Required by all projects that test actors
  val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion // ApacheV2
  // Required for cs, pkg (test with multiple jvms)
  val akkaMultiNodeTest = "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion

  // Required for all Scala tests (except those using specs2)
  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1" // ApacheV2
  // Required for all Java tests
  val junitInterface = "com.novocode" % "junit-interface" % "0.11"
}

