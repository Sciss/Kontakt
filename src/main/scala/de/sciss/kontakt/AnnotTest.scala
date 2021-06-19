package de.sciss.kontakt

object AnnotTest {
  def main(args: Array[String]): Unit = {
    val dayIdx = 10
    val s = io.Source.fromInputStream(getClass.getResourceAsStream("/annotations.txt"), "UTF-8").getLines().drop(dayIdx).next()
    println(s"Annotation: '$s'")
  }
}
