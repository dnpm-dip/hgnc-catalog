
/*
 build.sbt adapted from https://github.com/pbassiner/sbt-multi-project-example/blob/master/build.sbt
*/


name := "hgnc-gene-set"
ThisBuild / organization := "de.dnpm.dip"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version := "1.0-SNAPSHOT"


//-----------------------------------------------------------------------------
// PROJECTS
//-----------------------------------------------------------------------------

lazy val global = project
  .in(file("."))
  .settings(
    settings,
    publish / skip := true
  )
  .aggregate(
     impl,
     tests
  )


lazy val impl = project
  .settings(
    name := "hgnc-gene-set-impl",
    settings,
    libraryDependencies ++= Seq(
      dependencies.scalatest,
      dependencies.core,
    )
  )


lazy val tests = project
  .settings(
    name := "tests",
    settings,
    libraryDependencies ++= Seq(
      dependencies.scalatest,
    ),
    publish / skip := true
  )
  .dependsOn(
    impl % Test,
  )


//-----------------------------------------------------------------------------
// DEPENDENCIES
//-----------------------------------------------------------------------------

lazy val dependencies =
  new {
    val scalatest    = "org.scalatest"  %% "scalatest"  % "3.1.1" % Test
    val core        = "de.dnpm.dip"    %% "core"       % "1.0-SNAPSHOT"
  }


//-----------------------------------------------------------------------------
// SETTINGS
//-----------------------------------------------------------------------------

lazy val settings = commonSettings

lazy val compilerOptions = Seq(
  "-encoding", "utf8",
  "-unchecked",
  "-feature",
  "-language:postfixOps",
  "-Xfatal-warnings",
  "-deprecation",
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++=
    Seq("Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository") ++
    Resolver.sonatypeOssRepos("releases") ++
    Resolver.sonatypeOssRepos("snapshots")
)

