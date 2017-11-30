name := "pactole"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % Version.akka,

  "org.scalatest" %% "scalatest" % Version.scalaTest % "test",
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka % "test"
)