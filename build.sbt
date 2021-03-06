import com.typesafe.tools.mima.core._
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import com.typesafe.sbt.SbtGit.GitKeys.{gitCurrentBranch, gitHeadCommit}

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; scalafmtSbt")
addCommandAlias("fmtCheck", "; compile:scalafmtCheck; test:scalafmtCheck; scalafmtSbtCheck")

lazy val contributors = Seq(
  "mpilquist" -> "Michael Pilquist",
  "pchiusano" -> "Paul Chiusano"
)

lazy val commonSettings = Seq(
  organization := "org.scodec",
  organizationHomepage := Some(new URL("http://scodec.org")),
  licenses += ("Three-clause BSD-style", url(
    "https://github.com/scodec/scodec/blob/master/LICENSE"
  )),
  git.remoteRepo := "git@github.com:scodec/scodec.git",
  scmInfo := Some(
    ScmInfo(url("https://github.com/scodec/scodec"), "git@github.com:scodec/scodec.git")
  ),
  unmanagedResources in Compile ++= {
    val base = baseDirectory.value
    (base / "NOTICE") +: (base / "LICENSE") +: ((base / "licenses") * "LICENSE_*").get
  },
  crossScalaVersions := List("2.12.10", "2.13.1"),
  scalacOptions ++= Seq(
    "-encoding",
    "UTF-8",
    "-deprecation",
    "-feature",
    "-language:higherKinds",
    "-unchecked"
  ) ++
    (scalaBinaryVersion.value match {
      case v if v.startsWith("2.13") =>
        List("-Xlint", "-Ywarn-unused")
      case v if v.startsWith("2.12") =>
        Nil
      case v if v.startsWith("0.") =>
        Nil
      case other => sys.error(s"Unsupported scala version: $other")
    }),
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  releaseCrossBuild := true,
  mimaPreviousArtifacts := Set.empty
) ++ publishingSettings

lazy val publishingSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots".at(nexus + "content/repositories/snapshots"))
    else
      Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
  },
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { x =>
    false
  },
  pomExtra := (
    <url>http://github.com/scodec/scodec</url>
    <developers>
      {for ((username, name) <- contributors) yield <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>}
    </developers>
  ),
  pomPostProcess := { (node) =>
    import scala.xml._
    import scala.xml.transform._
    def stripIf(f: Node => Boolean) = new RewriteRule {
      override def transform(n: Node) =
        if (f(n)) NodeSeq.Empty else n
    }
    val stripTestScope = stripIf { n =>
      n.label == "dependency" && (n \ "scope").text == "test"
    }
    new RuleTransformer(stripTestScope).transform(node)(0)
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(testkitJVM, testkitJS, coreJVM, coreJS, unitTests)
  .settings(commonSettings: _*)
  .settings(
    publishArtifact := false
  )

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings: _*)
  .settings(
    name := "scodec-core",
    unmanagedSourceDirectories in Compile += {
      val dir = CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v >= 13 => "scala-2.13+"
        case _                       => "scala-2.12-"
      }
      baseDirectory.value / "../shared/src/main" / dir
    },
    libraryDependencies ++= Seq(
      "org.scodec" %%% "scodec-bits" % "1.1.12",
      "com.chuusai" %%% "shapeless" % "2.3.3"
    ),
    buildInfoPackage := "scodec",
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit),
    scalacOptions in (Compile, doc) := {
      val tagOrBranch = {
        if (version.value.endsWith("SNAPSHOT")) gitCurrentBranch.value
        else ("v" + version.value)
      }
      Seq(
        "-groups",
        "-implicits",
        "-implicits-show-all",
        "-sourcepath",
        new File(baseDirectory.value, "../..").getCanonicalPath,
        "-doc-source-url",
        "https://github.com/scodec/scodec/tree/" + tagOrBranch + "€{FILE_PATH}.scala"
      )
    }
  )
  .jvmSettings(
    OsgiKeys.exportPackage := Seq("!scodec.bits,scodec.*;version=${Bundle-Version}"),
    mimaPreviousArtifacts := {
      List().map { pv =>
        organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
      }.toSet
    },
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingMethodProblem]("scodec.codecs.UuidCodec.codec"),
      ProblemFilters.exclude[MissingMethodProblem]("scodec.Attempt.toTry")
    )
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

lazy val testkit = crossProject(JVMPlatform, JSPlatform)
  .in(file("testkit"))
  .settings(commonSettings: _*)
  .settings(
    name := "scodec-testkit",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % "1.14.3",
      "org.scalatest" %%% "scalatest" % "3.1.0",
      "org.scalatestplus" %%% "scalacheck-1-14" % "3.1.0.1"
    )
  )
  .dependsOn(core % "compile->compile")

lazy val testkitJVM = testkit.jvm
lazy val testkitJS = testkit.js

lazy val unitTests = project
  .in(file("unitTests"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.64" % "test"
    ),
    libraryDependencies ++= (if (scalaBinaryVersion.value.startsWith("2.10"))
                               Seq(
                                 compilerPlugin(
                                   ("org.scalamacros" % "paradise" % "2.0.1")
                                     .cross(CrossVersion.patch)
                                 )
                               )
                             else Nil)
  )
  .dependsOn(testkitJVM % "test->compile")
  .settings(publishArtifact := false)

lazy val benchmark: Project = project
  .in(file("benchmark"))
  .dependsOn(coreJVM)
  .enablePlugins(JmhPlugin)
  .settings(commonSettings: _*)
  .settings(publishArtifact := false)
