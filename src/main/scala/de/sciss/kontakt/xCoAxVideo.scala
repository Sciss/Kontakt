/*
 *  xCoAxVideo.scala
 *  (Kontakt)
 *
 *  Copyright (c) 2021-2022 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.kontakt

import de.sciss.file._
import de.sciss.kontakt.Window.Content
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.awt.Color
import java.awt.image.{BufferedImage, ImageObserver}
import javax.imageio.ImageIO
import scala.concurrent.ExecutionContext.Implicits.global

/** Rendering the video version for the prolonged xCoAx exhibition.
  *
  * You'll need to have updated all entries using the update procedure
  * as indicated in the `README.md`.
  */
object xCoAxVideo {
  case class Config(
                   fps: Int = 25,
                   tempOut: File = file("frame-%d.png")
                   ) {

    def formatTempOut(frame: Int): File= {
      val name = tempOut.name.format(frame)
      tempOut.parentOption.fold(file(name))(_ / name)
    }
  }

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "xCoAxVideo"
      private val default = Config()
      val fps: Opt[Int] = opt(default = Some(default.fps),
        descr = s"Frames per second (default: ${default.fps}).",
        validate = _ > 0
      )
      val tempOut: Opt[File] = opt(required = true,
        descr = s"File output template where '%d' is replaced by the frame index."
      )

      verify()
      val config: Config = Config(
        fps     = fps(),
        tempOut = tempOut(),
      )
    }
    run()(p.config)
  }

  def run()(implicit config: Config): Unit = {
    implicit val wCfg: Window.Config = Window.Config(
      verbose       = true,
      shutdownHour  = 0,
      skipUpdate    = true,
      initDelay     = 0,
      updateMinutes = 0,
      threshEntries = false,
      hasView       = false,
      crossEyed     = true,
    )
    val r = Window.run()
    val v = new ViewImpl
    r.view = Some(v)
    val futAll = r.initialEntries()
    futAll.foreach { entries =>
      println(s"entries.size ${entries.size}")
      r.setRandomEntries(entries)
    }

    // keep running
    new Thread {
      override def run(): Unit = this.synchronized(this.wait())
      start()
    }
  }

  private final class ViewSideImpl(protected val alignRight: Boolean)(implicit protected val config: Window.Config)
    extends Window.ViewSideBase {

    override protected def repaint(): Unit = ()

    override protected def imageObserver: ImageObserver = null
  }

  private final class ViewImpl(implicit config: Window.Config, vConfig: xCoAxVideo.Config) extends Window.View {
    private val width     = config.panelWidth * 2
    private val height    = config.panelHeight
    private val img       = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val textFont  = Window.font1pt.deriveFont(config.fontSize.toFloat)
    private val gImg      = {
      val g = img.createGraphics()
      g.setBackground(Color.black)
      g.setFont(textFont)
      g
    }

    private val leftView  = mkSideView(isLeft = true  )
    private val rightView = mkSideView(isLeft = false )

    override def left: Option[Content] = leftView.data
    override def left_=(c: Option[Content]): Unit = leftView.data_=(c)

    override def right: Option[Content] = rightView.data
    override def right_=(c: Option[Content]): Unit = rightView.data_=(c)

    override def leftOverlay : String = leftView.overlay
    override def leftOverlay_=(s: String): Unit = leftView.overlay = s

    override def rightOverlay : String = rightView.overlay
    override def rightOverlay_=(s: String): Unit = rightView.overlay = s

    private def mkSideView(isLeft: Boolean) = {
      val res = new ViewSideImpl(alignRight = isLeft)
      res
    }

    private var frameCount = 0

    private val formatName = if (vConfig.tempOut.extL == "png") "png" else "jpg"

    override def sync(): Unit = {
      frameCount += 1
      println(s"sync $frameCount")
      gImg.clearRect(0, 0, width, height)
      val atOrig = gImg.getTransform
      leftView.paintSide(gImg)
      gImg.translate(config.panelWidth, 0)
      rightView.paintSide(gImg)
      gImg.setTransform(atOrig)

      val fOut = vConfig.formatTempOut(frameCount)
      ImageIO.write(img, formatName, fOut)
    }

    override def installKeyboardDials(m: Dials.Model): Unit = ()
  }
}
