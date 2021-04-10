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

import ij_transforms.Transform_Perspective
import circledetection.Hough_Transform
import ij.io.Opener
import ij.process.ImageProcessor
import mpicbg.ij.InverseTransformMapping

import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import scala.math.abs

object HoughTest {
  case class Point2D(x: Int, y: Int) {
    def distanceSq(that: Point2D): Long = {
      val dx = abs(this.x - that.x)
      val dy = abs(this.y - that.y)
      dx * dx + dy * dy
    }
  }

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

//    val path    = "/data/projects/Kontakt/materials/snap_210407_192807m.jpg"
    val pathIn  = "/data/projects/Kontakt/materials/snap_210409_154852crop_m.jpg"
    val pathOut = "/data/temp/_killme.jpg"
    val opener  = new Opener
    val imgP    = opener.openImage(pathIn)
    try {
      val w       = imgP.getWidth
      val h       = imgP.getHeight
      val ip      = imgP.getProcessor
      val ht      = new Hough_Transform()
      ht.setParameters(30, 50, 4)
      val htRes = ht.runHeadless(ip)
      val circleCenters = htRes.iterator.map { case Array(x, y, z) => Point2D(x, y) } .toList

      /*

        expected output:

        - first image -

        x 1682, y 582, radius 46
        x 157, y 615, radius 46
        x 108, y 198, radius 47
        x 1693, y 192, radius 46

        - second image -

        x 202, y 123, radius 49
        x 1069, y 956, radius 46
        x 1080, y 115, radius 48
        x 235, y 976, radius 45

       */

      println("Circle centers:")
      for (Array(x, y, r) <- htRes) {
        println(s"x $x, y $y, radius $r")
      }

      require (circleCenters.size == 4)
      val topLeftIn   = circleCenters.minBy(_.distanceSq(Point2D(0, 0)))
      val topRightIn  = circleCenters.minBy(_.distanceSq(Point2D(w, 0)))
      val botLeftIn   = circleCenters.minBy(_.distanceSq(Point2D(0, h)))
      val botRightIn  = circleCenters.minBy(_.distanceSq(Point2D(w, h)))
      require (List(topLeftIn, topRightIn, botLeftIn, botRightIn).distinct.size == 4)

      println(s"top    left  in : $topLeftIn")
      println(s"top    right in : $topRightIn")
      println(s"bottom left  in : $botLeftIn")
      println(s"bottom right in : $botRightIn")

      val inWidthT  = topRightIn.x - topLeftIn .x
      val inWidthB  = botRightIn.x - botLeftIn .x
      val inHeightL = botLeftIn .y - topLeftIn .y
      val inHeightR = botRightIn.y - topRightIn.y
      val inWidthM  = (inWidthT  + inWidthB ) * 0.5
      val inHeightM = (inHeightL + inHeightR) * 0.5

      println(f"input width  (mean): $inWidthM%1.1f")   // e.g. 856
      println(f"input height (mean): $inHeightM%1.1f")  // e.g. 847

      val normSideLength = 850
      val normExtent = normSideLength/2

//      val dxTL = ((inWidthT - normSideLength) * 0.5 + 0.5).toInt
//      val dxTR = topLeftIn.x + dxTL + normSideLength - topRightIn.x
//
//      val dxBL = ((inWidthB - normSideLength) * 0.5 + 0.5).toInt
//      val dxBR = botLeftIn.x + dxBL + normSideLength - botRightIn.x
//
//      val dyTL = ((inHeightL - normSideLength) * 0.5 + 0.5).toInt
//      val dyBL = topLeftIn.y + dyTL + normSideLength - botLeftIn.y
//
//      val dyTR = ((inHeightR - normSideLength) * 0.5 + 0.5).toInt
//      val dyBR = topRightIn.y + dyTR + normSideLength - botRightIn.y

      val cx = (topLeftIn.x + topRightIn.x + botLeftIn.x + botRightIn.x) / 4
      val cy = (topLeftIn.y + topRightIn.y + botLeftIn.y + botRightIn.y) / 4
      val dxTL = (cx - normExtent) - topLeftIn .x
      val dyTL = (cy - normExtent) - topLeftIn .y
      val dxTR = (cx + normExtent) - topRightIn.x
      val dyTR = (cy - normExtent) - topRightIn.y
      val dxBL = (cx - normExtent) - botLeftIn. x
      val dyBL = (cy + normExtent) - botLeftIn .y
      val dxBR = (cx + normExtent) - botRightIn.x
      val dyBR = (cy + normExtent) - botRightIn.y

      val topLeftOut = Point2D(
        topLeftIn.x + dxTL,
        topLeftIn.y + dyTL,
      )
      val topRightOut = Point2D(
        topRightIn.x + dxTR,
        topRightIn.y + dyTR,
      )
      val botLeftOut = Point2D(
        botLeftIn.x + dxBL,
        botLeftIn.y + dyBL,
      )
      val botRightOut = Point2D(
        botRightIn.x + dxBR,
        botRightIn.y + dyBR,
      )

      println(s"top    left  out: $topLeftOut")
      println(s"top    right out: $topRightOut")
      println(s"bottom left  out: $botLeftOut")
      println(s"bottom right out: $botRightOut")

      val tp = new Transform_Perspective
      tp.setPointMatches(
        Array(topLeftIn .x, topRightIn .x, botRightIn .x, botLeftIn .x),
        Array(topLeftIn .y, topRightIn .y, botRightIn .y, botLeftIn .y),
        Array(topLeftOut.x, topRightOut.x, botRightOut.x, botLeftOut.x),
        Array(topLeftOut.y, topRightOut.y, botRightOut.y, botLeftOut.y),
      )

      val source = ip.duplicate()
      val target = source.createProcessor(w, h)
      source.setInterpolationMethod(ImageProcessor.BICUBIC)
      val mapping = new InverseTransformMapping(tp.getModel)
      mapping.mapInterpolated(source, target)
      ip.setPixels(target.getPixels)
      val imgOut = ip.getBufferedImage
      ImageIO.write(imgOut, "jpg", new File(pathOut))

    } finally {
      imgP.close()
    }
  }
}