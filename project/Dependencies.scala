import sbt._

object Dependencies {

  val cswVersion = "0.5"
  val scalaVersion = "2.12.2"
  val akkaVersion = "2.5.1"

  val pkg = "org.tmt" %% "pkg" % cswVersion
  val cs = "org.tmt" %% "cs" % cswVersion
  val ccs = "org.tmt" %% "ccs" % cswVersion
  val log = "org.tmt" %% "log" % cswVersion
  val ts = "org.tmt" %% "ts" % cswVersion
  val alarms = "org.tmt" %% "alarms" % cswVersion
  val events = "org.tmt" %% "events" % cswVersion
  val containerCmd = "org.tmt" %% "containercmd" % cswVersion
  val seqSupport = "org.tmt" %% "seqsupport" % cswVersion
  val javacsw = "org.tmt" %% "javacsw" % cswVersion

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

