package de.sciss.kontakt

import ij_transforms.Transform_Perspective
import ij.ImageJ

object TransformTest {
  def main(args: Array[String]): Unit = {
    val clazz = classOf[Transform_Perspective]
    val url = clazz.getResource("/" + clazz.getName.replace('.', '/') + ".class").toString
    System.setProperty("plugins.dir", url.substring(5, url.length - clazz.getName.length - 6))
    new ImageJ
  }
}
