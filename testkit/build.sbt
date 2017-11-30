name := "pactole-testkit"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % Version.akka,
  "org.scalatest" %% "scalatest" % Version.scalaTest,
  "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka,
  "com.miguno.akka" %% "akka-mock-scheduler" % Version.mockScheduler

)