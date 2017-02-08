import sbt.Keys._
import sbt._

import Dependencies._
import Settings._

val csw = (project in file("."))
  .settings(defaultSettings: _*)
  .settings(name := "CSW Vertical Slice Example")
  .aggregate(vslice, vsliceJava )

def compile(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "compile")

def test(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "test")

def runtime(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "runtime")

def container(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "container")

// EndToEnd Example project
lazy val vslice = project
  .enablePlugins(JavaAppPackaging)
  .settings(packageSettings("VerticalSlice", "Vertical Slice Example", "More complicated example showing CSW features"): _*)
  .settings(libraryDependencies ++=
    compile(pkg, cs, ccs, ts, events, alarms, containerCmd, seqSupport, log) ++
      test(scalaTest, akkaTestKit)
  )

// EndToEnd Example project Java version
lazy val vsliceJava = project
  .enablePlugins(JavaAppPackaging)
  .settings(packageSettings("VerticalSliceJava", "Vertical Slice Java Example", "More complicated example showing CSW Java features"): _*)
  .settings(libraryDependencies ++=
    compile(javacsw, log) ++
      test(akkaTestKit, junitInterface, scalaJava8Compat)
  )

