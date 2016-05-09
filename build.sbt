import Benchmark._ // see project/Benchmark.scala
import Dependencies._ // see project/Dependencies.scala
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

val buildVersion = "0.11.0-M4"

def commonSettings = Seq(
  version in ThisBuild := buildVersion,
  scalaVersion := Util.buildScalaVersion,
  crossScalaVersions := Util.buildScalaVersions,
  scalacOptions in (Test, compile) ++= (scalaBinaryVersion.value match {
    case "2.10" => Seq("-Xmax-classfile-name", "254")
    case _ => Seq()
  }),
  organization in ThisBuild := "org.scala-lang.modules",
  organizationName in ThisBuild := "LAMP/EPFL",
  organizationHomepage in ThisBuild := Some(url("http://lamp.epfl.ch")),
  homepage in ThisBuild := Some(url("https://github.com/scala/pickling")),
  licenses in ThisBuild := List("BSD-like" -> url("http://www.scala-lang.org/downloads/license.html")),
  scmInfo in ThisBuild := Some(ScmInfo(url("https://github.com/scala/pickling"), "git@github.com:scala/pickling.git")),
  developers in ThisBuild := List(
    Developer("xeno-by", "Eugene Burmako", "@xeno_by", url("http://github.com/xeno-by")),
    Developer("heathermiller", "Heather Miller", "@heathercmiller", url("http://github.com/heathermiller")),
    Developer("phaller", "Philipp Haller", "@philippkhaller", url("http://github.com/phaller")),
    Developer("havocp", "Havoc Pennington", "@havocp", url("https://github.com/havocp")),
    Developer("eed3si9n", "Eugene Yokota", "@eed3si9n", url("https://github.com/eed3si9n")),
    Developer("jsuereth", "Josh Suereth", "@jsuereth", url("https://github.com/jsuereth"))
  ),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.sonatypeRepo("releases"),
  scalacOptions ++= Seq("-feature"),
  parallelExecution in Test := false, // hello, reflection sync!!
  publishMavenStyle in ThisBuild := true,
  publishArtifact in Test := false,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { x => false },
  pomExtra := <inceptionYear>2013</inceptionYear>,
  credentials ++= Util.loadCredentials(),
  pomPostProcess := { (node: XmlNode) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: XmlNode): XmlNodeSeq = node match {
        case e: Elem if e.label == "dependency" && e.child.exists(child => child.label == "artifactId" && child.text.contains("testutil")) =>
          Comment(s"Ommitted test-util dependency")
        case _ => node
      }
    }).transform(node).head
  }
)
def noPublish = Seq(
  publish := {},
  publishLocal := {}
)

// Use root project
lazy val root: Project = (project in file(".")).
  aggregate(coreJVM, benchmark, sandbox, sandboxTests, macroTests).
  settings(commonSettings ++ noPublish: _*).
  settings(
    name := "Scala Pickling",
    run in Compile := (run in (sandbox, Compile)).evaluated
  )


lazy val testUtil = (crossProject in file("test-util")).
  settings(commonSettings ++ noPublish: _*)

lazy val testUtilJVM = testUtil.jvm
lazy val testUtilJS = testUtil.js

/** Scala Pickling code */
lazy val core = (crossProject in file("core")).
  dependsOn(testUtil % "test->test").
  settings(commonSettings: _*).
  settings(
    name := "scala-pickling"
  ).
  settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value // for ToolBox  
    )
  ).
  jvmSettings(
    libraryDependencies ++= {
      val baseDeps = Seq(
        scalaTest % Test,
        scalaCheck % Test
      )
      val additional = CrossVersion.partialVersion(scalaVersion.value) match {
        // if scala 2.11+ is used, quasiquotes are merged into scala-reflect
        case Some((2, scalaMajor)) if scalaMajor >= 11 =>
          Seq(parserCombinators)
        // in Scala 2.10, quasiquotes are provided by macro-paradise
        case Some((2, 10)) =>
          Seq(compilerPlugin(macroParadise), quasiquotes)
      }
      baseDeps ++ additional
    }
  ).
  jsSettings(
    scalacOptions in (Test, compile) ++= Seq("-Xmax-classfile-name", "254"),
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % "3.0.0-M15" % Test,
      "org.scalacheck" %%% "scalacheck" % "1.12.5" % Test
    )
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

lazy val macroTests: Project = (project in file("macro-test")).
  dependsOn(coreJVM).
  settings(commonSettings:_*).settings(noPublish:_*).
  settings(
    libraryDependencies ++= {
      val baseDeps =
        Seq(
          scalaTest % Test,
          "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test
        )
      val additional = CrossVersion.partialVersion(scalaVersion.value) match {
        // if scala 2.11+ is used, quasiquotes are merged into scala-reflect
        case Some((2, scalaMajor)) if scalaMajor >= 11 =>
          Seq(parserCombinators)
        // in Scala 2.10, quasiquotes are provided by macro-paradise
        case Some((2, 10)) =>
          Seq(compilerPlugin(macroParadise), quasiquotes)
      }
      baseDeps ++ additional
    }
  )

lazy val sandbox: Project = (project in file("sandbox")).
  dependsOn(coreJVM).
  settings(commonSettings ++ noPublish: _*).
  settings(
    sourceDirectory in Test := baseDirectory.value,
    libraryDependencies += scalaTest % Test,
    // scalacOptions ++= Seq()
    scalacOptions ++= Seq("-Xlog-implicits")
    // scalacOptions ++= Seq("-Xprint:typer")
  )

/* This submodule is meant to store tests that need to be executed
 * independently from the main test suite placed in `core`. */
lazy val sandboxTests: Project = (project in file("sandbox-test")).
  dependsOn(coreJVM).
  settings(commonSettings ++ noPublish: _*).
  settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      scalaTest % Test,
      scalaCheck % Test
    )
  )

lazy val benchmark: Project = (project in file("benchmark")).
  dependsOn(coreJVM).
  settings(commonSettings ++ noPublish ++ benchmarkSettings: _*).
  settings(
    scalacOptions ++= Seq("-optimise"),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      kryoSerializers, kryo)
  )
