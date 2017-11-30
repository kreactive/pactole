name := "pactole-http"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % Version.playJson,
  "com.typesafe.akka" %% "akka-http" % Version.akkaHttp,

  //test dependencies
  "com.typesafe.akka" %% "akka-http-testkit" % Version.akkaHttp % "test"
)