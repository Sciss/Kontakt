/*
 *  HoughTest.scala
 *  (Kontakt)
 *
 *  Copyright (c) 2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.kontakt

import circledetection.Hough_Transform
import ij.io.Opener

object HoughTest {
  def main(args: Array[String]): Unit = {
    val path    = "/data/projects/Kontakt/materials/snap_210407_192807m.jpg"
    val opener  = new Opener
    val imgP    = opener.openImage(path)
    try {
      val ip      = imgP.getProcessor
      val ht      = new Hough_Transform()
      ht.setParameters(35, 55, 4)
      val res = ht.runHeadless(ip)

      /*
      
        expected output:

        x 1682, y 582, radius 46
        x 157, y 615, radius 46
        x 108, y 198, radius 47
        x 1693, y 192, radius 46

       */


      for (Array(x, y, r) <- res) {
        println(s"x $x, y $y, radius $r")
      }
    } finally {
      imgP.close()
    }
  }
}