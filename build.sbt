lazy val baseName       = "Kontakt"
lazy val projectVersion = "0.1.0-SNAPSHOT"

lazy val root = project
  .in(file("."))
  .settings(
    name         := baseName,
    description  := "A waiting piece of lichen growth",
    version      := projectVersion,
    homepage     := Some(url(s"https://git.iem.at/sciss/$baseName")),
    licenses     := Seq("AGPL v3+" -> url("http://www.gnu.org/licenses/agpl-3.0.txt")),
//    scalaVersion := "3.0.0-RC2",
    scalaVersion := "2.13.5", // IntelliJ is not ready for Dotty :(
    resolvers    += "imagej.releases" at "https://maven.scijava.org/content/repositories/releases/",
    libraryDependencies ++= Seq(
      "net.imagej" % "ij" % deps.main.imageJ
    )
  )

lazy val deps = new {
  lazy val main = new {
    val imageJ = "1.47h"
  }
}
