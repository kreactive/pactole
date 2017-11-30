name := "pactole-project"

version in ThisBuild := "1.1.0"

organization in ThisBuild := "com.kreactive"

scalaVersion in ThisBuild := "2.11.8"

crossScalaVersions in ThisBuild := Seq("2.11.8", "2.12.2")

scalacOptions in ThisBuild ++= Seq("-deprecation")

lazy val testkit = project in file("testkit")

lazy val lib = project in file("lib") dependsOn (testkit % "test")

lazy val http = project in file("http")


licenses in ThisBuild := List(
  ("Apache License, Version 2.0",
    url("https://www.apache.org/licenses/LICENSE-2.0"))
)

homepage in ThisBuild := Some(url("https://github.com/kreactive"))

publishTo := Some("kreactive bintray" at "https://api.bintray.com/maven/kreactive/maven/pactole")

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

publishMavenStyle := true

publishArtifact := false