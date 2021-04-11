package de.sciss.kontakt

import ij_transforms.Transform_Perspective
import ij.ImageJ

// runs ImageJ UI with the `Transform_Perspective` plug-in present, to test its operation
object TransformTest {
  def main(args: Array[String]): Unit = {
    val clazz = classOf[Transform_Perspective]
    val url = clazz.getResource("/" + clazz.getName.replace('.', '/') + ".class").toString
    System.setProperty("plugins.dir", url.substring(5, url.length - clazz.getName.length - 6))
    new ImageJ
  }
}
