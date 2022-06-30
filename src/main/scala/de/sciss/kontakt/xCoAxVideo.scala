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
import de.sciss.kontakt.Window.{Content, log}
import de.sciss.numbers.Implicits._
import de.sciss.synth.Curve
import org.rogach.scallop
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.awt.image.{BufferedImage, ImageObserver}
import java.awt.{Color, Toolkit}
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.{IIOImage, ImageIO, ImageTypeSpecifier, ImageWriteParam, ImageWriter}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.swing.Swing
import scala.util.Random

/** Rendering the video version for the prolonged xCoAx exhibition.
  *
  * You'll need to have updated all entries using the update procedure
  * as indicated in the `README.md`.
  */
object xCoAxVideo {
  case class Config(
                   fps            : Int   = 25,
                   tempOut        : File  = file("frame-%d.png"),
                   idleMinDur     : Float = 6f,     // seconds
                   idleMaxDur     : Float = 120f,   // seconds
                   idleDurCurve   : Curve = Curve.exp, // cubed,
                   dialMinDur     : Float = 0.5f,
                   dialMaxDur     : Float = 8f,
                   dialDurCurve   : Curve = Curve.sine,
                   dialMinClickHz : Float = 1f,
                   dialMaxClickHz : Float = 15f,
                   dialClickCurve : Curve = Curve.squared,
                   dialMinRatio   : Float = 1f,
                   dialMaxRatio   : Float = 4f,
                   dialRatioCurve : Curve = Curve.exp,
                   dialAccelProb  : Float = 0.7f, // ritardando prob = 1 - x
                   moveMinIndices : Int   = 1,
                   moveMaxIndices : Int   = 122 * 4, // c. four months
                   videoDur       : Int   = 60 * 10,  // seconds
                   jpgQuality     : Int   = 90,
                   ) {

    def formatTempOut(frame: Int): File = {
      val name = tempOut.name.format(frame)
      tempOut.parentOption.fold(file(name))(_ / name)
    }
  }

  private val curveNameMap: Map[String, Curve] = Map(
    "step"        -> Curve.step,
    "lin"         -> Curve.linear,
    "linear"      -> Curve.linear,
    "exp"         -> Curve.exponential,
    "exponential" -> Curve.exponential,
    "sin"         -> Curve.sine,
    "sine"        -> Curve.sine,
    "welch"       -> Curve.welch,
    "sqr"         -> Curve.squared,
    "squared"     -> Curve.squared,
    "cub"         -> Curve.cubed,
    "cubed"       -> Curve.cubed
  )

  implicit val ReadCurve: scallop.ValueConverter[Curve] = scallop.singleArgConverter { s =>
    curveNameMap.getOrElse(s.toLowerCase, {
      val p = s.toFloat
      Curve.parametric(p)
    })
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
      val idleMinDur    : Opt[Float]  = opt(default = Some(default.idleMinDur    ),
        descr = s"Idle minimum duration, in seconds (default: ${default.idleMinDur})",
        validate = _ > 0
      )
      val idleMaxDur    : Opt[Float]  = opt(default = Some(default.idleMaxDur    ),
        descr = s"Idle maximum duration, in seconds (default: ${default.idleMaxDur})",
        validate = _ > 0
      )
      val idleDurCurve  : Opt[Curve]  = opt(default = Some(default.idleDurCurve  ),
        descr = s"Idle min/max duration scaling curve (default: ${default.idleDurCurve})"
      )
      val dialMinDur    : Opt[Float]  = opt(default = Some(default.dialMinDur    ),
        descr = s"Dial turning minimum duration, in seconds (default: ${default.dialMinDur})",
        validate = _ > 0
      )
      val dialMaxDur    : Opt[Float]  = opt(default = Some(default.dialMaxDur    ),
        descr = s"Dial turning maximum duration, in seconds (default: ${default.dialMaxDur})",
        validate = _ > 0
      )
      val dialDurCurve  : Opt[Curve]  = opt(default = Some(default.dialDurCurve  ),
        descr = s"Dial turning min/max duration scaling curve (default: ${default.dialDurCurve})"
      )
      val dialMinClickHz: Opt[Float] = opt(default = Some(default.dialMinClickHz),
        descr = s"Dial turning minimum click frequency, in Hz (default: ${default.dialMinClickHz})",
        validate = _ > 0
      )
      val dialMaxClickHz: Opt[Float] = opt(default = Some(default.dialMaxClickHz),
        descr = s"Dial turning maximum click frequency, in Hz (default: ${default.dialMaxClickHz})",
        validate = _ > 0
      )
      val dialClickCurve  : Opt[Curve]  = opt(default = Some(default.dialClickCurve),
        descr = s"Dial turning min/max click freq scaling curve (default: ${default.dialClickCurve})"
      )
      val dialMinRatio  : Opt[Float]  = opt(default = Some(default.dialMinRatio  ),
        descr = s"Dial slow-down/speed-up minimum ratio (default: ${default.dialMinRatio})",
        validate = _ >= 1
      )
      val dialMaxRatio  : Opt[Float]  = opt(default = Some(default.dialMaxRatio  ),
        descr = s"Dial slow-down/speed-up maximum ratio (default: ${default.dialMaxRatio})",
        validate = _ >= 1
      )
      val dialRatioCurve: Opt[Curve]  = opt(default = Some(default.dialRatioCurve),
        descr = s"Dial slow-down/speed-up min/max ratio scaling curve (default: ${default.dialRatioCurve})"
      )
      val dialAccelProb : Opt[Float]  = opt(default = Some(default.dialAccelProb ),
        descr = s"Probability of dial speed-up as opposed to slow-down (default: ${default.dialAccelProb})",
        validate = x => x >= 0 && x <= 1
      )
      val moveMinIndices: Opt[Int]    = opt(default = Some(default.moveMinIndices),
        descr = s"Minimum number of image indices between left and right (default: ${default.moveMinIndices})",
        validate = _ >= 0
      )
      val moveMaxIndices: Opt[Int]    = opt(default = Some(default.moveMaxIndices),
        descr = s"Maximum number of image indices between left and right (default: ${default.moveMaxIndices})",
        validate = _ > 0
      )
      val videoDur      : Opt[Int]    = opt(default = Some(default.videoDur      ),
        descr = s"Video duration in seconds (default: ${default.videoDur})",
        validate = _ > 0
      )
      val jpgQuality: Opt[Int] = opt(default = Some(default.jpgQuality),
        descr = s"JPEG quality 0 to 100  (default: ${default.jpgQuality})",
        validate = x => x >=0 && x <= 100
      )

      verify()
      val config: Config = Config(
        fps             = fps           (),
        tempOut         = tempOut       (),
        idleMinDur      = idleMinDur    (),
        idleMaxDur      = idleMaxDur    (),
        idleDurCurve    = idleDurCurve  (),
        dialMinDur      = dialMinDur    (),
        dialMaxDur      = dialMaxDur    (),
        dialDurCurve    = dialDurCurve  (),
        dialMinClickHz  = dialMinClickHz(),
        dialMaxClickHz  = dialMaxClickHz(),
        dialClickCurve  = dialClickCurve(),
        dialMinRatio    = dialMinRatio  (),
        dialMaxRatio    = dialMaxRatio  (),
        dialRatioCurve  = dialRatioCurve(),
        dialAccelProb   = dialAccelProb (),
        moveMinIndices  = moveMinIndices(),
        moveMaxIndices  = moveMaxIndices(),
        videoDur        = videoDur      (),
        jpgQuality      = jpgQuality    (),
      )
    }
    run()(p.config)
  }

  implicit class CurveOps(private val c: Curve) extends AnyVal {
    def map(in: Double, lo: Double, hi: Double): Double =
      c.levelAt(in.toFloat, y1 = lo.toFloat, y2 = hi.toFloat)

    def choose(lo: Double, hi: Double)(implicit rnd: Random): Double = {
      val x = rnd.nextDouble()
      map(x, lo = lo, hi = hi)
    }
  }

  def run()(implicit config: Config): Unit = {
    implicit val wCfg: Window.Config = Window.Config(
      verbose         = true,
      shutdownHour    = 0,
      skipUpdate      = true,
      initDelay       = 0,
      updateMinutes   = 0,
      threshEntries   = false,
      hasView         = false,
      crossEyed       = true,
      trigFetchDur    = 10,   // basically instantaneous
      idleMoveMinutes = 2,
      seed            = 0xC0FFEE,
    )
    import config._
    val sync = new AnyRef
    val s = new OfflineScheduler(fps = fps, sync = sync)
    val r = Window.run(s, sync = sync)
    val v = new ViewImpl(s)
    r.view = Some(v)
    val futAll = r.initialEntries()
    futAll.foreach { entries =>
      println(s"entries.size ${entries.size}")
      r.setRandomEntries(entries)
//      scheduler.schedule(1000L) {
//        r.setRandomEntries(entries)
//      }

      // fps
      // idleMinDur, idleMaxDur, idleDurCurve
      // dialMinDur, dialMaxDur, dialDurCurve
      // dialMinClickHz, dialMaxClickHz, dialClickCurve
      // dialMinRatio, dialMaxRatio, dialRatioCurve
      // dialAccelProb
      // moveMinIndices, moveMaxIndices
      // videoDur

      val videoFrames = (videoDur * fps + 0.5).toInt

      implicit val rnd: Random = r.random
      def makeDial(): Unit = {
        val isLeft        = rnd.nextBoolean()
        val dialDur       = dialDurCurve  .choose(dialMinDur    , dialMaxDur    )
        val dialRatio     = dialRatioCurve.choose(dialMinRatio  , dialMaxRatio  )
        val dialClickFreq = dialClickCurve.choose(dialMinClickHz, dialMaxClickHz)
        val isAccel       = rnd.nextDouble() < dialAccelProb
        val entries       = r.currentEntries
        var eLeftIdx      = r.entryLeftIdx
        var eRightIdx     = r.entryRightIdx
        val dialClickDur0 = dialClickFreq.reciprocal
        val dialClickDur1 = if (isAccel) dialClickDur0 / dialRatio else dialClickDur0 * dialRatio
        var dir           = rnd.nextBoolean()

        log(f"dial dur $dialDur%1.1fs, beginning at freq $dialClickFreq%1.1f Hz, accel? $isAccel left? $isLeft, initial dir $dir with left idx $eLeftIdx right idx $eRightIdx")

        var sameCount = 0
        var speed     = 1
        var lastTime  = 0L
        var lastDir   = !dir
        var secs  = 0.0
//        println("---> DIAL")
        val now0 = System.currentTimeMillis()
        while (secs < dialDur) {
          val idxBefore = if (isLeft) eLeftIdx else eRightIdx
          val idxOther  = if (isLeft) eRightIdx else eLeftIdx

          def resetSpeed(): Unit = {
            sameCount = 0
            speed     = 1
          }

          if (lastDir != dir) {
            lastDir = dir
            resetSpeed()
          }
          // the time-out is higher if we've been already actively scrolling,
          // so one can move the hand back on the dial after a half rotation
          val now = now0 + (secs * 1000).toLong
          val dt = if (sameCount >= 6) 800 else 400
          if ((now - lastTime) < dt) {
            sameCount += 1
            if (sameCount >= 24) {
              if (speed < 32) speed <<= 1
              sameCount = 6
            }
          } else {
            resetSpeed()
          }
          lastTime  = now

          if (dir && idxBefore + speed >= entries.size) {
            dir = !dir
          }
          if (!dir && idxBefore - speed < 0) {
            dir = !dir
          }
          val idxTest = idxBefore + speed * (if (dir) 1 else -1)
          val idxDiff = idxOther absDif idxTest
          if (idxDiff < moveMinIndices || idxDiff > moveMaxIndices) {
            dir = !dir
          }
          val idxNow0 = idxBefore + speed * (if (dir) 1 else -1)
          val idxNow  = idxNow0.clip(0, entries.size - 1)
          val dialAmtActual = -(idxNow - idxBefore) // API assumes opposite direction!
          val dial    = if (isLeft) Dials.Left(dialAmtActual) else Dials.Right(dialAmtActual)
          println(dial)
          if (isLeft) eLeftIdx = idxNow else eRightIdx = idxNow
          s.scheduleSec(secs) {
//            println("---> dial")
            r.dial(dial)
//            println("<--- dial")
          }
          val dialClickDur = secs.linLin(0.0, dialDur, dialClickDur0, dialClickDur1).max(0.01)
          secs += dialClickDur
//          dialClickDur = (if (isAccel) dialClickDur / dialRatio else dialClickDur * dialRatio).max(0.01)
        }
//        println("<-- DIAL")

        s.scheduleSec(secs) {
          makeIdle()
        }
      }

      def render(): Unit =
        Swing.onEDT {
          val fut = v.run()
          fut.onComplete { tr =>
            println(s"Rendering completed. $tr")
            val code = if (tr.isSuccess) 0 else 1
            sys.exit(code)
          }
        }

      def makeIdle(): Unit = {
        val idleDur = idleDurCurve.choose(idleMinDur, idleMaxDur)
        log(f"idle dur $idleDur%1.1fs")
        s.scheduleSec(idleDur) {
          if (s.frame < videoFrames) makeDial() else {
            println(s"Schedules ${s.frame} frames")
            render()
          }
        }
      }

//      dialRange.foreach { dt =>
//        s.schedule(dt) {
//          r.dial(Dials.Left(if (dialDir) +1 else -1))
//        }
//      }

      s.scheduleSec(videoDur) { () }  // make sure that's there

      makeIdle()
      render()

//      Swing.onEDT {
//        v.run()
//      }

//      val advanced = scheduler.advance(2000L) {
//        v.sync()
//      }
//      println(s"Advanced by $advanced frames")
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

  private final class ViewImpl(scheduler: OfflineScheduler)
                              (implicit config: Window.Config, vConfig: xCoAxVideo.Config)
    extends Window.View {

    private val width     = config.panelWidth * 2
    private val height    = config.panelHeight
    private val img       = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    private val textFont  = Window.font1pt.deriveFont(config.fontSize.toFloat)
    private val gImg      = {
      val g = img.createGraphics()
      g.setBackground(Color.black)
      g.setFont(textFont)
      g
    }

    private[this] val tk = Toolkit.getDefaultToolkit

    def run(): Future[Unit] = {
      val p = Promise[Unit]()
      def runImpl(): Unit = {
        /*val n =*/ scheduler.advanceFrames(1) {
          sync()
        }
        //      if (n > 0) Swing.onEDT(run())
        if (scheduler.nonEmpty) {
          Swing.onEDT {
            run()
          }
          tk.sync()
        } else {
          p.trySuccess(())
        }
      }

      runImpl()
      p.future
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

    private val formatName = if (vConfig.tempOut.extL == "png") "png" else "jpg"

    private val imgParam = if (formatName == "png") null else {
      val p = new JPEGImageWriteParam(null)
      p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
      p.setCompressionQuality(vConfig.jpgQuality * 0.01f)
      p
    }

    private val writer: ImageWriter = {
      val it = ImageIO.getImageWriters(ImageTypeSpecifier.createFromRenderedImage(img), formatName)
      if (!it.hasNext) throw new IllegalArgumentException(s"No image writer for $formatName")
      it.next()
    }

    override def sync(): Unit = {
      val frameCount = scheduler.frame
      log(s"sync $frameCount")
//      gImg.clearRect(0, 0, width, height)
      gImg.setColor(Color.black)
      gImg.fillRect(0, 0, width, height)
      val atOrig = gImg.getTransform
      leftView.paintSide(gImg)
      gImg.translate(config.panelWidth, 0)
      rightView.paintSide(gImg)
      gImg.setTransform(atOrig)

      val fOut = vConfig.formatTempOut(frameCount)
//      ImageIO.write(img, formatName, fOut)
      fOut.delete()
      val out = new FileImageOutputStream(fOut)
      writer.setOutput(out)
      writer.write(null /* meta */ , new IIOImage(img, null /* thumb */ , null /* meta */), imgParam)
      writer.reset()
    }

    override def installKeyboardDials(m: Dials.Model): Unit = ()
  }
}
