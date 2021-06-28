lazy val baseName       = "Kontakt"
lazy val baseNameL      = baseName.toLowerCase
lazy val projectVersion = "0.4.1"

lazy val buildInfoSettings = Seq(
  // ---- build info ----
  buildInfoKeys := Seq(name, organization, version, scalaVersion, description,
    BuildInfoKey.map(homepage) { case (k, opt)           => k -> opt.get },
    BuildInfoKey.map(licenses) { case (_, Seq((lic, _))) => "license" -> lic }
  ),
  buildInfoOptions += BuildInfoOption.BuildTime
)

lazy val root = project.in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(buildInfoSettings)
  .settings(assemblySettings)
  .settings(
    name         := baseName,
    description  := "A waiting piece of lichen growth",
    version      := projectVersion,
    homepage     := Some(url(s"https://git.iem.at/sciss/$baseName")),
    licenses     := Seq("AGPL v3+" -> url("http://www.gnu.org/licenses/agpl-3.0.txt")),
    scalaVersion := "2.13.6",
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8"),
    resolvers    += "imagej.releases" at "https://maven.scijava.org/content/repositories/releases/",
    libraryDependencies ++= Seq(
      "com.pi4j"            %  "pi4j-core"            % deps.main.pi4j,         // GPIO control
      "de.sciss"            %% "fileutil"             % deps.main.fileUtil,     // utility functions
      "de.sciss"            %% "numbers"              % deps.main.numbers,      // numeric utilities
      "de.sciss"            %% "scaladon"             % deps.main.scaladon,     // Mastodon client
      "de.sciss"            %% "serial"               % deps.main.serial,       // Serialization
      "mpicbg"              %  "mpicbg"               % deps.main.mpicbg,       // 2D transforms
      "net.harawata"        %  "appdirs"              % deps.main.appDirs,      // finding standard directories
      "net.imagej"          %  "ij"                   % deps.main.imageJ,       // analyzing image data
//      "org.apache.commons"  % "commons-text"          % deps.main.commonsText,  // decode HTML entities
      "org.unbescape"       % "unbescape"             % deps.main.unbescape,    // decode HTML entities
      "org.rogach"          %% "scallop"              % deps.main.scallop,      // command line option parsing
      "org.scala-lang.modules" %% "scala-swing"       % deps.main.scalaSwing,   // UI
    ),
    buildInfoPackage := "de.sciss.kontakt",
  )

lazy val deps = new {
  lazy val main = new {
    val appDirs     = "1.2.1"
//    val commonsText = "1.9"
    val fileUtil    = "1.1.5"
    val imageJ      = "1.53j" // "1.47h"
    val mpicbg      = "1.4.1"
    val numbers     = "0.2.1"
    val pi4j        = "1.4"
    val scaladon    = "0.5.0"
    val scalaSwing  = "3.0.0"
    val scallop     = "4.0.3"
    val serial      = "2.0.1"
    val unbescape   = "1.1.6.RELEASE"
  }
}

def appMainClass = Some("de.sciss.kontakt.PiRun")

lazy val assemblySettings = Seq(
  // ---- assembly ----
  assembly / test            := {},
  assembly / mainClass       := appMainClass,
  assembly / target          := baseDirectory.value,
  assembly / assemblyJarName := s"$baseNameL.jar",
  assembly / assemblyMergeStrategy := {
    case "logback.xml" => MergeStrategy.last
    case PathList("org", "xmlpull", _ @ _*)              => MergeStrategy.first
    case PathList("org", "w3c", "dom", "events", _ @ _*) => MergeStrategy.first // bloody Apache Batik
    case PathList(ps @ _*) if ps.last endsWith "module-info.class" => MergeStrategy.first // bloody Jackson
    case x =>
      val old = (assembly / assemblyMergeStrategy).value
      old(x)
  },
  assembly / fullClasspath := (Test / fullClasspath).value // https://github.com/sbt/sbt-assembly/issues/27
)
