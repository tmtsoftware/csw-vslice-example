import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences._
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import sbt._


//noinspection TypeAnnotation
// Defines the global build settings so they don't need to be edited everywhere
object Settings {
  val Version = "0.3-SNAPSHOT"

  val buildSettings = Seq(
    organization := "org.tmt",
    organizationName := "TMT",
    organizationHomepage := Some(url("http://www.tmt.org")),
    version := Version,
    scalaVersion := Dependencies.scalaVersion,
    crossPaths := true,

    // Note: "parallelExecution in Test := false" doesn't seem to prevent parallel execution when all tests are run,
    // which can be a problem in some cases. Besides the fact that all the test output is mixed up,
    // some tests access external resources, such as the location service, redis, hornetq, the config service, etc.,
    // and running these tests in parallel can cause spurious errors (although it would be much faster, if it worked).
    parallelExecution in Test := false,
    // See http://stackoverflow.com/questions/11899723/how-to-turn-off-parallel-execution-of-tests-for-multi-project-builds
    parallelExecution in ThisBuild := false,
    // See https://github.com/sbt/sbt/issues/1886
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
    // Don't buffer test log output (since not parallel)
    logBuffered in Test := false,
    
    fork := true,
    autoAPIMappings := true,
    resolvers += Resolver.typesafeRepo("releases"),
    resolvers += Resolver.sonatypeRepo("releases"),
    resolvers += sbtResolver.value
  )

  lazy val defaultSettings = buildSettings ++ formatSettings ++ Seq(
    scalacOptions ++= Seq("-target:jvm-1.8", "-encoding", "UTF-8", "-feature", "-deprecation", "-unchecked"),
    scalacOptions in(Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value + "/root-doc.txt", "-no-link-warnings"),
    javacOptions in Compile ++= Seq("-source", "1.8"),
    javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"),
    javacOptions in (Compile, doc) ++= Seq("-Xdoclint:none"),
    javaOptions in (Test, run) ++= Seq("-Djava.net.preferIPv4Stack=true"),  // For location service
    testOptions in Test := Seq(Tests.Argument(TestFrameworks.JUnit, "-a", "-v"), Tests.Argument("-oI"), Tests.Argument("-Djava.net.preferIPv4Stack=true"))
  )

  // For standalone applications
  def packageSettings(name: String, summary: String, desc: String) = defaultSettings ++ Seq(
    version in Rpm := Version,
    rpmRelease := "0",
    rpmVendor := "TMT Common Software",
    rpmUrl := Some("http://www.tmt.org"),
    rpmLicense := Some("ApacheV2"),
    rpmGroup := Some("CSW"),
    packageSummary := summary,
    packageDescription := desc,
    bashScriptExtraDefines ++= Seq("addJava -Djava.net.preferIPv4Stack=true"),
    bashScriptExtraDefines ++= Seq(s"addJava -DCSW_VERSION=$Version"),
    bashScriptExtraDefines ++= Seq(s"addJava -Dapplication-name=$name")
  )

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences
  )

  def formattingPreferences: FormattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)

}
