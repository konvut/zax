import sbt.Keys._
import sbt._

lazy val common = Seq(
  organization  := "io.landy",
  version       := "0.1.3",

  scalaVersion      := "2.11.7",
  autoScalaLibrary  := false,

  scalacOptions     := Seq("-target:jvm-1.7")
                    ++ Seq("-encoding", "utf8")
                    ++ Seq("-unchecked", "-deprecation", "-feature")
                    ++ Seq("-optimise"),
//                    ++ Seq("-Xlog-implicits"),
//                    ++ Seq("-Yinline-warnings")
//                    ++ Seq("-Xexperimental")

  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }
)

/// Project ///////////////////////////////////////////////////////////////////////////////////////

lazy val root = (project in file("."))
  .settings(common: _*)
  .settings(
    name := "zax"
  )

/// Docker  ///////////////////////////////////////////////////////////////////////////////////////

enablePlugins(JavaServerAppPackaging)
enablePlugins(DockerPlugin)

mainClass in Compile := Some("io.landy.app.App")

import com.typesafe.sbt.packager.docker._

packageName in Docker := name.value
version     in Docker := version.value

defaultLinuxInstallLocation in Docker := "/opt/docker/"

dockerBaseImage := "java:8"

dockerExposedPorts := Seq(8080, 8081, 8090)

dockerCommands :=
  dockerCommands.value.filterNot {
    case Cmd("USER", _) => true
    case _ => false
  } ++ Seq(
    ExecCmd ("RUN", "mkdir", "-p",                  s"/var/log/${name.value}"),
    ExecCmd ("RUN", "chown", "-R", "daemon:daemon", s"/var/log/${name.value}"),
    Cmd     ("USER", "daemon")
  )


/// Resolvers /////////////////////////////////////////////////////////////////////////////////////

resolvers += "sonatype-releases"  at "https://oss.sonatype.org/content/repositories/releases/"
resolvers += "sonatype-snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

unmanagedJars in Compile <<= baseDirectory map { base => (base ** "*.jar").classpath }

libraryDependencies ++= Seq(
  "org.scalaz"          %%  "scalaz-core"    % "7.1.3" withSources(),
  "org.specs2"          %%  "specs2-core"    % "2.4.16" % "test"
)

libraryDependencies ++= {
  val ver = "2.4.1"
  Seq(
    "com.typesafe.akka"   %%  "akka-actor"     % ver withSources(),
    "com.typesafe.akka"   %%  "akka-testkit"   % ver % "test"
  )
}

libraryDependencies ++= {
  val sprayVer      = "1.3.3"
  val sprayJsonVer  = "1.3.2"
  Seq(
    "io.spray"            %%  "spray-can"      % sprayVer withSources(),
    "io.spray"            %%  "spray-routing"  % sprayVer withSources(),
    "io.spray"            %%  "spray-caching"  % sprayVer withSources(),
    "io.spray"            %%  "spray-testkit"  % sprayVer  % "test",
    "io.spray"            %%  "spray-json"     % sprayJsonVer withSources()
  )
}

libraryDependencies ++= {
  val sparkVersion = "1.6.0"
  Seq(
    "org.apache.spark" %% "spark-core" % sparkVersion withSources(),
    "org.apache.spark" %% "spark-mllib" % sparkVersion withSources()
  )
}

libraryDependencies ++= {
  Seq(
    "com.typesafe.play"  %% "play-iteratees" % "2.4.2",
    "org.reactivemongo"  %% "reactivemongo"  % "0.11.9"
  )
}

libraryDependencies ++= {
  Seq(
    //
    // TODO(kudinkin): replaced as unmanaged-dep until 0.11.* version lands
    //
    // "org.scala-lang.modules" %% "scala-pickling" % "0.10.3-SNAPSHOT" withSources(),
  )
}

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka" %% "akka-slf4j"       % "2.4.1",
    "ch.qos.logback"    %  "logback-classic"  % "1.1.3"
  )
}

libraryDependencies ++= {
  Seq(
    "com.amazonaws" % "aws-java-sdk-s3" % "1.10.47"
  )
}

libraryDependencies ++= {
  Seq(
    "com.maxmind.geoip2" % "geoip2" % "2.4.0"
  )
}

libraryDependencies ++= {
  // https://scalameter.github.io/
  // http://scalameter.github.io/home/gettingstarted/0.7/configuration/index.html
  Seq(
    //"com.storm-enroute" %% "scalameter" % "0.7-SNAPSHOT" % "test"
  )
}


/// Overrides /////////////////////////////////////////////////////////////////////////////////////

dependencyOverrides ++= Set(
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.4.4"
)

/// Local Bindings ////////////////////////////////////////////////////////////////////////////////

lazy val runMongo = taskKey[Unit]("Starts the MongoDB instance locally")

runMongo := {
  println("Starting MongoD")
  "mongod --fork --config /usr/local/etc/mongod.conf"!
}

lazy val stopMongo = taskKey[Unit]("Stops the MongoDB local instance")

stopMongo := {
  println("Stopping MongoD")
  "mongod --shutdown"!
}

lazy val runWithMongo = taskKey[Unit]("Runs the app starting MongoDB-daemon locally!")

runWithMongo := Def.sequential(runMongo, (run in Compile).toTask(""), stopMongo).value


lazy val runSpark = taskKey[Unit]("Starts the local instance of Spark's master")

runSpark := {
  println("Starting Spark Master")
  "$SPARK_HOME/sbin/start-master.sh -p 7077 --webui-port 8082"!
}

lazy val stopSpark = taskKey[Unit]("Stops the local instance of Spark's master")

stopSpark := {
  println("Stopping Spark Master")
  "$SPARK_HOME/sbin/stop-master.sh"!
}

lazy val runWithSpark = taskKey[Unit]("Runs the app starting Spark's Master instance locally!")

runWithSpark := Def.sequential(runSpark, (run in Compile).toTask(""), stopSpark).value


/// Settings //////////////////////////////////////////////////////////////////////////////////////

cancelable in Global := true