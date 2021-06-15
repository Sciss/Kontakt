lazy val baseName       = "Kontakt"
lazy val baseNameL      = baseName.toLowerCase
lazy val projectVersion = "0.3.0-SNAPSHOT"

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
    resolvers    += "imagej.releases" at "https://maven.scijava.org/content/repositories/releases/",
    libraryDependencies ++= Seq(
      "com.pi4j"      %  "pi4j-core"            % deps.main.pi4j,       // GPIO control
//      "com.pi4j"      %  "pi4j-gpio-extension"  % deps.main.pi4j,     // PCA9685 support
//      "com.pi4j"      %  "pi4j-device"          % deps.main.pi4j,     // Servo motors
      "de.sciss"      %% "fileutil"             % deps.main.fileUtil,   // utility functions
      "de.sciss"      %% "numbers"              % deps.main.numbers,    // numeric utilities
      "de.sciss"      %% "scaladon"             % deps.main.scaladon,   // Mastodon client
      "mpicbg"        %  "mpicbg"               % deps.main.mpicbg,     // 2D transforms
      "net.harawata"  %  "appdirs"              % deps.main.appDirs,    // finding standard directories
      "net.imagej"    %  "ij"                   % deps.main.imageJ,     // analyzing image data
      "org.rogach"    %% "scallop"              % deps.main.scallop,    // command line option parsing
      "org.scala-lang.modules" %% "scala-swing" % deps.main.scalaSwing, // UI
    ),
    buildInfoPackage := "de.sciss.kontakt",
  )

lazy val deps = new {
  lazy val main = new {
    val appDirs     = "1.2.1"
    val fileUtil    = "1.1.5"
    val imageJ      = "1.53i" // "1.47h"
    val mpicbg      = "1.4.1"
    val numbers     = "0.2.1"
    val pi4j        = "1.4"
    val scaladon    = "0.5.0-SNAPSHOT"
    val scalaSwing  = "3.0.0"
    val scallop     = "4.0.2"
  }
}

def appMainClass = Some("de.sciss.kontakt.PiRun")

lazy val assemblySettings = Seq(
  // ---- assembly ----
  test            in assembly := {},
  mainClass       in assembly := appMainClass,
  target          in assembly := baseDirectory.value,
  assemblyJarName in assembly := s"$baseNameL.jar",
  assemblyMergeStrategy in assembly := {
    case "logback.xml" => MergeStrategy.last
    case PathList("org", "xmlpull", _ @ _*)              => MergeStrategy.first
    case PathList("org", "w3c", "dom", "events", _ @ _*) => MergeStrategy.first // bloody Apache Batik
    case PathList(ps @ _*) if ps.last endsWith "module-info.class" => MergeStrategy.first // bloody Jackson
    case x =>
      val old = (assemblyMergeStrategy in assembly).value
      old(x)
  },
  fullClasspath in assembly := (fullClasspath in Test).value // https://github.com/sbt/sbt-assembly/issues/27
)
