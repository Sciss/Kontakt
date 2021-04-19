/*
 *  PiRun.scala
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

import de.sciss.file._
import de.sciss.scaladon.Status
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.io.File
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.{Date, Locale}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object PiRun {
  case class Config(
                   username     : String  = "user",
                   password     : String  = "pass",
                   initDelay    : Int     =   120,
                   preCropLeft  : Int     =   500,
                   preCropRight : Int     =  1000,
                   shutterDay   : Int     =  5000,
                   shutterNight : Int     = 15000,
                   pumpTimeOut  : Int     =    90,
                   pump         : Boolean = true,
                   toot         : Boolean = true,
                   verbose      : Boolean = false,
                   shutdown     : Boolean = true,
                   )

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

    object p extends ScallopConf(args) {
      printedName = "PiRun"
      //      version(fullName)
      private val default = Config()

      val verbose: Opt[Boolean] = opt("verbose", short = 'V', default = Some(false),
        descr = "Verbose printing."
      )
      val username: Opt[String] = opt("user", short = 'u', required = true,
        descr = "Mastodon bot user name."
      )
      val password: Opt[String] = opt("pass", short = 'p', required = true,
        descr = "Mastodon bot password."
      )
      val initDelay: Opt[Int] = opt("init-delay", default = Some(default.initDelay),
        descr = s"Initial delay in seconds (to make sure date-time is synced) (default: ${default.initDelay})."
      )
      val preCropLeft: Opt[Int] = opt("pre-crop-left", default = Some(default.preCropLeft),
        descr = s"Pre-analysis pre-scaling crop of left side (default: ${default.preCropLeft})."
      )
      val preCropRight: Opt[Int] = opt("pre-crop-right", default = Some(default.preCropRight),
        descr = s"Pre-analysis pre-scaling crop of right side (default: ${default.preCropRight})."
      )
      val shutterDay: Opt[Int] = opt("shutter-day", default = Some(default.shutterDay),
        descr = s"Shutter time in microseconds during the day (default: ${default.shutterDay})."
      )
      val shutterNight: Opt[Int] = opt("shutter-night", default = Some(default.shutterNight),
        descr = s"Shutter time in microseconds during the night (default: ${default.shutterNight})."
      )
      val pumpTimeOut: Opt[Int] = opt("pump-timeout", default = Some(default.pumpTimeOut),
        descr = s"Maximum duration in seconds to wait for the irrigation to finish (default: ${default.pumpTimeOut})."
      )
      val noPump: Opt[Boolean] = opt("no-pump", descr = "Disable irrigation.", default = Some(false))
      val noToot: Opt[Boolean] = opt("no-toot", descr = "Disable Mastodon."  , default = Some(false))
      val noShutdown: Opt[Boolean] = opt("no-shutdown", descr = "Do not shutdown Pi after compleition.", default = Some(false))

      verify()
      val config: Config = Config(
        username      = username(),
        password      = password(),
        initDelay     = initDelay(),
        preCropLeft   = preCropLeft(),
        preCropRight  = preCropRight(),
        shutterDay    = shutterDay(),
        shutterNight  = shutterNight(),
        pumpTimeOut   = pumpTimeOut(),
        verbose       = verbose(),
        pump          = !noPump(),
        toot          = !noToot(),
        shutdown      = !noShutdown(),
      )
    }
    run(p.config)
  }

  def run(c: Config): Unit = {
    val initDelayMS = math.max(0, c.initDelay) * 1000L
    if (initDelayMS > 0) {
      println(s"Waiting for ${c.initDelay} seconds.")
      Thread.sleep(initDelayMS)
      println(s"The date and time: ${new Date()}")
    }

    if (c.pump) {
      println("Activating irrigation...")
      val resPump = Try {
        val dirPump = new File("scripts").getCanonicalFile
        val fPump   = dirPump / "pump_multi.sh"
        val pb = new ProcessBuilder(fPump.path)
          .directory(dirPump)
        val p  = pb.start()
        p.waitFor(c.pumpTimeOut, TimeUnit.SECONDS)
        if (p.isAlive) p.destroy()
        p.exitValue()
      }

      resPump match {
        case Success(0) =>
        case Success(code) =>
          Console.err.println(s"Pump script failed with code $code")
        case Failure(ex) =>
          Console.err.println("Pump script failed:")
          ex.printStackTrace()
      }
    }

    val odt       = OffsetDateTime.now()
    val date      = Date.from(odt.toInstant)
    val isDay     = odt.getHour > 4 && odt.getHour < 20
    println(s"Making photo (isDay? $isDay)...")
    val shutter   = if (isDay) c.shutterDay else c.shutterNight
    val dirPhoto  = new File("photos").getCanonicalFile
    val fPhoto    = stampedFile(dirPhoto, pre = "snap", ext = "jpg", date = date)
    val resPhoto = Try {
      val pb = new ProcessBuilder("raspistill",
        "-t", "400",
        "-w", "3840",
        "-h", "2160",
        "-n",
        "-fli", "off",
        "-drc", "off",
        "-awbg", "1.6,1.4",
        "-awb", "off",
        "-ex", "fixedfps",
        "-ss", shutter.toString,
        "-o", fPhoto.path,
      )
      val p  = pb.start()
      p.waitFor(20, TimeUnit.SECONDS)
      if (p.isAlive) p.destroy()
      p.exitValue()
    }

    val hasPhoto = resPhoto match {
      case Success(0) => true
      case Success(code) =>
        Console.err.println(s"Photo failed with code $code")
        false
      case Failure(ex) =>
        Console.err.println("Photo failed:")
        ex.printStackTrace()
        false
    }

    if (hasPhoto) {
      val fPhotoCrop = fPhoto.replaceName(s"${fPhoto.base}-crop.jpg")

      val cCrop = CropPhoto.Config(
        preCropLeft   = c.preCropLeft,
        preCropRight  = c.preCropRight,
        fileIn        = fPhoto,
        fileOut       = fPhotoCrop,
        verbose       = c.verbose,
      )

      println("Cropping photo...")
      val resCrop = Try { CropPhoto.run(cCrop) }
      val hasCrop = resCrop match {
        case Success(_) => true
        case Failure(ex) =>
          Console.err.println("Crop photo failed:")
          ex.printStackTrace()
          false
      }

      if (hasCrop) {
        println("Tooting photo...")
        val text = s"The time is $odt - we're still trying to make contact..."
        val cToot = TootPhoto.Config(
          username  = c.username,
          password  = c.password,
          imgFile   = fPhotoCrop,
          text      = text,
          verbose   = c.verbose,
        )

        val futToot = TootPhoto.run(cToot)
        val trToot  = Try { Await.ready(futToot, Duration(60, TimeUnit.SECONDS)) }
        val resToot: Try[Status] = trToot.flatMap { _ => futToot.value.get }

        resToot match {
          case Success(_) =>
            if (c.verbose) {
              println("Successfully sent toot.")
            }
          case Failure(ex) =>
            Console.err.println("Toot failed:")
            ex.printStackTrace()
        }
      }
    }

    println("Done.")
    if (c.shutdown) {
//      if (c.verbose) {
        println("Shutting down...")
        Thread.sleep(4000L)
//      }
      shutdown()
    } else {
      sys.exit()
    }
  }

  def shutdown(): Unit = {
    import sys.process._
    Seq("sudo", "shutdown", "now").!
  }

  def stampedFile(folder: File, pre: String, ext: String, date: Date): File = {
    val extP = if (ext.startsWith(".")) ext else s".$ext"
    require(!pre.contains('\''), "Invalid characters " + pre)
    require(!ext.contains('\''), "Invalid characters " + ext)
    val df = new SimpleDateFormat(s"'$pre-'yyMMdd'_'HHmmss'$extP'", Locale.US)
    new File(folder, df.format(date))
  }
}