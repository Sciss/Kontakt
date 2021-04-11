/*
 *  CropPhoto.scala
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
import de.sciss.file._
import ij.ImagePlus
import ij.io.FileInfo
import ij.process.ImageProcessor
import ij_transforms.Transform_Perspective
import mpicbg.ij.InverseTransformMapping
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.{IIOImage, ImageIO, ImageTypeSpecifier, ImageWriteParam}
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import scala.math.abs

object CropPhoto {
  case class IntPoint2D(x: Int, y: Int) {
    def distanceSq(that: IntPoint2D): Long = {
      val dx = abs(this.x - that.x)
      val dy = abs(this.y - that.y)
      dx * dx + dy * dy
    }
  }

  case class Point2D(x: Double, y: Double) {
    def distanceSq(that: Point2D): Double = {
      val dx = abs(this.x - that.x)
      val dy = abs(this.y - that.y)
      dx * dx + dy * dy
    }
  }

  case class Config(
                     fileIn         : File    = file("in.jpg"),
                     fileOut        : File    = file("out.jpg"),
                     verbose        : Boolean = false,
                     normExtent     : Int     =  850,
                     cropExtent     : Int     =  720,
                     houghScale     : Double  =    0.5,
                     houghMinRadius : Int     =   30,
                     houghMaxRadius : Int     =   50,
                     preCropLeft    : Int     =  500,
                     preCropRight   : Int     =  500, // 1000,
                     preCropTop     : Int     =    0,
                     preCropBottom  : Int     =    0,
                     quality        : Int     =   90,
                   ) {

    require (normExtent     >= 2)
    require (cropExtent     >= 2)
    require (houghScale     <= 1.0)
    require (houghMinRadius >= 0)
    require (houghMaxRadius >= houghMinRadius)
    require (preCropLeft    >= 0)
    require (preCropRight   >= 0)
    require (preCropTop     >= 0)
    require (preCropBottom  >= 0)

    def hasPreCrop: Boolean = preCropLeft > 0 || preCropRight > 0 || preCropTop > 0 || preCropBottom > 0
  }

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

    object p extends ScallopConf(args) {
      printedName = "CropPhoto"
//      version(fullName)
      private val default = Config()

      val fileIn: Opt[File] = opt("input", short = 'i', required = true,
        descr = "Unprocessed photo input file from Pi camera."
      )
      val fileOut: Opt[File] = opt("output", short = 'o', required = true,
        descr = "Processed photo output file."
      )
      val verbose: Opt[Boolean] = opt("verbose", short = 'V',
        descr = "Verbose printing."
      )
      val normExtent: Opt[Int] = opt("norm-extent", default = Some(default.normExtent),
        descr = s"Normalized extent (half side length) after Hough scaling (default: ${default.normExtent})."
      )
      val cropExtent: Opt[Int] = opt("crop-extent", default = Some(default.cropExtent),
        descr = s"Final cropped extent (half side length) for output image (default: ${default.cropExtent})."
      )
      val houghScale: Opt[Double] = opt("hough-scale", default = Some(default.houghScale),
        descr = s"Hough analysis down-scaling (default: ${default.houghScale})."
      )
      val houghMinRadius: Opt[Int] = opt("hough-min-radius", default = Some(default.houghMinRadius),
        descr = s"Minimum expected circle marker radius after Hough scaling (default: ${default.houghMinRadius})."
      )
      val houghMaxRadius: Opt[Int] = opt("hough-max-radius", default = Some(default.houghMaxRadius),
        descr = s"Minimum expected circle marker radius after Hough scaling (default: ${default.houghMaxRadius})."
      )
      val preCropLeft  : Opt[Int] = opt("pre-crop-left", default = Some(default.preCropLeft),
        descr = s"Pre-analysis pre-scaling crop of left side (default: ${default.preCropLeft})."
      )
      val preCropRight  : Opt[Int] = opt("pre-crop-right", default = Some(default.preCropRight),
        descr = s"Pre-analysis pre-scaling crop of right side (default: ${default.preCropRight})."
      )
      val preCropTop    : Opt[Int] = opt("pre-crop-top", default = Some(default.preCropTop),
        descr = s"Pre-analysis pre-scaling crop of top side (default: ${default.preCropTop})."
      )
      val preCropBottom : Opt[Int] = opt("pre-crop-bottom", default = Some(default.preCropBottom),
        descr = s"Pre-analysis pre-scaling crop of bottom side (default: ${default.preCropBottom})."
      )
      val quality: Opt[Int] = opt("quality", short = 'q', default = Some(default.quality),
        descr = s"Output JPEG quality 0 to 100 (default: ${default.quality})."
      )

      verify()
      val config: Config = Config(
        fileIn          = fileIn(),
        fileOut         = fileOut(),
        verbose         = verbose(),
        normExtent      = normExtent(),
        cropExtent      = cropExtent(),
        houghScale      = houghScale(),
        houghMinRadius  = houghMinRadius(),
        houghMaxRadius  = houghMaxRadius(),
        preCropLeft     = preCropLeft   (),
        preCropRight    = preCropRight  (),
        preCropTop      = preCropTop    (),
        preCropBottom   = preCropBottom (),
        quality         = quality(),
      )
    }
    run(p.config)
  }

  def run(config: Config): Unit = {
    import config._
    val imgIn0  = ImageIO.read(fileIn)
    if (verbose) {
      println(s"Raw input size ${imgIn0.getWidth}, ${imgIn0.getHeight}")
    }
    val imgInC  = if (!hasPreCrop) imgIn0 else {
      val _wC   = imgIn0.getWidth  - (preCropLeft + preCropRight  )
      val _hC   = imgIn0.getHeight - (preCropTop  + preCropBottom )
      val res   = new BufferedImage(_wC, _hC, BufferedImage.TYPE_INT_RGB)
      val g     = res.createGraphics()
      g.drawImage(imgIn0, -preCropLeft, -preCropTop, null)
      g.dispose()
      res
    }
    val wC   = imgInC.getWidth
    val hC   = imgInC.getHeight

    if (verbose) {
      println(s"Cropped input size ${imgInC.getWidth}, ${imgInC.getHeight}")
    }
    val imgInS  = if (houghScale == 1.0) imgInC else {
      val wS    = (imgInC.getWidth  * houghScale).toInt
      val hS    = (imgInC.getHeight * houghScale).toInt
      val res   = new BufferedImage(wS, hS, BufferedImage.TYPE_INT_RGB)
      val g     = res.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.setRenderingHint(RenderingHints.KEY_RENDERING    , RenderingHints.VALUE_RENDER_QUALITY)
      g.drawImage(imgInC, 0, 0, wS, hS, null)
      g.dispose()
      res
    }
    if (verbose) {
      println(s"Hough-scaled input size ${imgInS.getWidth}, ${imgInS.getHeight}")
    }

    def toImageJ(in: BufferedImage): ImagePlus = {
      val res = new ImagePlus(fileIn.name, in)
      val fi  = new FileInfo
      fi.fileFormat = FileInfo.IMAGEIO
      fi.fileName   = fileIn.name
      fileIn.parentOption.foreach { p => fi.directory = p.path + File.separator }
      res.setFileInfo(fi)
      res
    }

    val imgInSP: ImagePlus = toImageJ(imgInS)

    val wS      = imgInSP.getWidth
    val hS      = imgInSP.getHeight
    if (verbose) {
      println(s"Hough input size $wS, $hS")
    }
    val procInS = imgInSP.getProcessor
    val ht      = new Hough_Transform()
    ht.setParameters(houghMinRadius, houghMaxRadius, 4)
    val htRes = ht.runHeadless(procInS)
    val circleCenters = htRes.iterator.map { case Array(x, y, _) =>
      Point2D(x / houghScale, y / houghScale)
    } .toList

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

    if (verbose) {
      println("Circle centers:")
      for (Array(x, y, r) <- htRes) {
        println(s"x $x, y $y, radius $r")
      }
    }

    val numCircles  = circleCenters.size
    require (numCircles == 4, numCircles)
    val topLeftIn   = circleCenters.minBy(_.distanceSq(Point2D(0d, 0d)))
    val topRightIn  = circleCenters.minBy(_.distanceSq(Point2D(wC, 0d)))
    val botLeftIn   = circleCenters.minBy(_.distanceSq(Point2D(0d, hC)))
    val botRightIn  = circleCenters.minBy(_.distanceSq(Point2D(wC, hC)))
    val ptIn        = List(topLeftIn, topRightIn, botLeftIn, botRightIn)
    require (ptIn.distinct.size == 4, ptIn)

    if (verbose) {
      println(s"top    left  in : $topLeftIn")
      println(s"top    right in : $topRightIn")
      println(s"bottom left  in : $botLeftIn")
      println(s"bottom right in : $botRightIn")
    }

    val inWidthT  = topRightIn.x - topLeftIn .x
    val inWidthB  = botRightIn.x - botLeftIn .x
    val inHeightL = botLeftIn .y - topLeftIn .y
    val inHeightR = botRightIn.y - topRightIn.y
    val inWidthM  = (inWidthT  + inWidthB ) * 0.5
    val inHeightM = (inHeightL + inHeightR) * 0.5

    if (verbose) {
      println(f"input width  (mean): $inWidthM%1.1f")   // e.g. 856
      println(f"input height (mean): $inHeightM%1.1f")  // e.g. 847
    }

//      val normSideLength  = 850
//      val normExtent      = normSideLength/2
//      val normSideLength  = normExtent << 1

    val cx    = (topLeftIn.x + topRightIn.x + botLeftIn.x + botRightIn.x) / 4
    val cy    = (topLeftIn.y + topRightIn.y + botLeftIn.y + botRightIn.y) / 4
    val dxTL  = (cx - normExtent) - topLeftIn .x
    val dyTL  = (cy - normExtent) - topLeftIn .y
    val dxTR  = (cx + normExtent) - topRightIn.x
    val dyTR  = (cy - normExtent) - topRightIn.y
    val dxBL  = (cx - normExtent) - botLeftIn. x
    val dyBL  = (cy + normExtent) - botLeftIn .y
    val dxBR  = (cx + normExtent) - botRightIn.x
    val dyBR  = (cy + normExtent) - botRightIn.y

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

    if (verbose) {
      println(s"top    left  out: $topLeftOut")
      println(s"top    right out: $topRightOut")
      println(s"bottom left  out: $botLeftOut")
      println(s"bottom right out: $botRightOut")
    }

    def round(xs: Array[Double]): Array[Int] =
      xs.map(x => (x + 0.5).toInt)

    val tp = new Transform_Perspective
    tp.setPointMatches(
      round(Array(topLeftIn .x, topRightIn .x, botRightIn .x, botLeftIn .x)),
      round(Array(topLeftIn .y, topRightIn .y, botRightIn .y, botLeftIn .y)),
      round(Array(topLeftOut.x, topRightOut.x, botRightOut.x, botLeftOut.x)),
      round(Array(topLeftOut.y, topRightOut.y, botRightOut.y, botLeftOut.y)),
    )

    val imgInCP   = toImageJ(imgInC)
    val procTrn   = imgInCP.getProcessor

    {
      val source  = procTrn.duplicate()
      val target  = source.createProcessor(wC, hC)
      source.setInterpolationMethod(ImageProcessor.BICUBIC)
      val mapping = new InverseTransformMapping(tp.getModel)
      mapping.mapInterpolated(source, target)
      procTrn.setPixels(target.getPixels)
    }

    val imgTrn  = procTrn.createImage()
    val sideOut = cropExtent << 1
    val imgOut  = {
      val res = new BufferedImage(sideOut, sideOut, BufferedImage.TYPE_INT_RGB)
      val g   = res.createGraphics()
      val x   = (cropExtent - cx).toInt
      val y   = (cropExtent - cy).toInt
      g.drawImage(imgTrn, x, y, null)
      g.dispose()
      res
    }

    {
      val (fmtOut, imgParam) = fileOut.extL match {
        case ext @ "png" => (ext, null)
        case _ =>
          val p = new JPEGImageWriteParam(null)
          p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
          p.setCompressionQuality(quality * 0.01f)
          ("jpg", p)
      }

      val it = ImageIO.getImageWriters(ImageTypeSpecifier.createFromRenderedImage(imgOut), fmtOut)
      if (!it.hasNext) throw new IllegalArgumentException(s"No image writer for $fmtOut")
      val imgWriter = it.next()
      fileOut.delete()
      val fos = new FileImageOutputStream(fileOut)
      try {
        imgWriter.setOutput(fos)
        imgWriter.write(null /* meta */ ,
          new IIOImage(imgOut, null /* thumb */ , null /* meta */), imgParam)
        imgWriter.dispose()
      } finally {
        fos.close()
      }
    }

//    ImageIO.write(imgOut, fmtOut, fileOut)
  }
}