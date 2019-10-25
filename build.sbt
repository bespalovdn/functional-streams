import sbt.Keys._
import sbt._

organization := "com.github.bespalovdn"
name := "functional-streams"
version := "1.0.0.75-SNAPSHOT"

scalaVersion := "2.12.10"
crossScalaVersions := Seq("2.12.10")

scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:postfixOps",
    "-unchecked"
)

Test / fork := true
Test / parallelExecution := false
Test / testForkedParallel := false

resolvers ++= Seq(
    "sonatype-snapshots"      at "https://oss.sonatype.org/content/repositories/snapshots",
    "sonatype-releases"       at "https://oss.sonatype.org/content/repositories/releases",
)

libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-api" % "1.7.5",
    "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
    else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomExtra := (
    <url>https://github.com/bespalovdn/functional-streams</url>
    <licenses>
        <license>
            <name>Apache 2.0 License</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
            <distribution>repo</distribution>
        </license>
    </licenses>
    <scm>
        <url>git@github.com:bespalovdn/functional-streams.git</url>
        <connection>scm:git:git@github.com:bespalovdn/functional-streams.git</connection>
    </scm>
    <developers>
        <developer>
            <id>bespalovdn</id>
            <name>Dmitry Bespalov</name>
            <email>bespalovdn@gmail.com</email>
        </developer>
    </developers>
)