/*
 *  PiRun.scala
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

import akka.actor.ActorSystem
import de.sciss.file._
import de.sciss.kontakt.Common.{fullVersion, shutdown}
import de.sciss.scaladon.{Id, Status}
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.io.{File, PrintStream}
import java.text.SimpleDateFormat
import java.time.{Duration, OffsetDateTime}
import java.util.concurrent.TimeUnit
import java.util.{Date, Locale}
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration => SDuration}
import scala.util.{Failure, Success, Try}

/** This is the code for observing the experiment, taking and uploading photos.
  */
object PiRun {
  case class Config(
                   username       : String  = "user",
                   password       : String  = "pass",
                   initDelay      : Int     =   120,
                   preCropLeft    : Int     =   500,
                   preCropRight   : Int     =   500,
                   shutterMorning : Int     = 12500,
                   shutterDay     : Int     = 10000,
                   shutterEvening : Int     = 12500,
                   shutterNight   : Int     = 15000,
                   pumpTimeOut    : Int     =    90,
                   httpConnTimeOut: Int     =    20,  // ! Akka default of 10s is too low
                   httpIdleTimeOut: Int     =    60,
                   pump           : Boolean = true,
                   toot           : Boolean = true,
                   verbose        : Boolean = false,
                   shutdown       : Boolean = true,
                   tootAttempts   : Int     = 2,
                   baseURI        : String  = "botsin.space",
                   accountId      : Id      = Id("304274"),
                   ) extends Login.Config

  final def name          : String = "Kontakt"
  final def nameAndVersion: String = s"$name $fullVersion"

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

    object p extends ScallopConf(args) {
      printedName = PiRun.nameAndVersion

      private val default = Config()

      implicit val idConverter: ValueConverter[Id] =
        singleArgConverter(new Id(_), PartialFunction.empty)  // Note: important to provide default arg (Dotty)

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
      val shutterMorning: Opt[Int] = opt("shutter-morning", default = Some(default.shutterMorning),
        descr = s"Shutter time in microseconds during the morning (default: ${default.shutterMorning})."
      )
      val shutterDay: Opt[Int] = opt("shutter-day", default = Some(default.shutterDay),
        descr = s"Shutter time in microseconds during the day (default: ${default.shutterDay})."
      )
      val shutterEvening: Opt[Int] = opt("shutter-evening", default = Some(default.shutterEvening),
        descr = s"Shutter time in microseconds during the evening (default: ${default.shutterEvening})."
      )
      val shutterNight: Opt[Int] = opt("shutter-night", default = Some(default.shutterNight),
        descr = s"Shutter time in microseconds during the night (default: ${default.shutterNight})."
      )
      val pumpTimeOut: Opt[Int] = opt("pump-timeout", default = Some(default.pumpTimeOut),
        descr = s"Maximum duration in seconds to wait for the irrigation to finish (default: ${default.pumpTimeOut})."
      )
      val noPump: Opt[Boolean] = opt("no-pump", descr = "Disable irrigation.",
        default = Some(!default.pump)
      )
      val noToot: Opt[Boolean] = opt("no-toot", descr = "Disable Mastodon.",
        default = Some(!default.toot)
      )
      val noShutdown: Opt[Boolean] = opt("no-shutdown", descr = "Do not shutdown Pi after completion.",
        default = Some(!default.shutdown)
      )
      val httpIdleTimeOut: Opt[Int] = opt("http-idle-timeout", default = Some(default.httpIdleTimeOut),
        descr = s"Http client idle time-out in seconds (default: ${default.httpIdleTimeOut})."
      )
      val httpConnTimeOut: Opt[Int] = opt("http-conn-timeout", default = Some(default.httpConnTimeOut),
        descr = s"Http client connection time-out in seconds (default: ${default.httpConnTimeOut})."
      )
      val tootAttempts: Opt[Int] = opt("toot-attempts", default = Some(default.tootAttempts),
        descr = s"Attempts to toot photo, if there are network problems (default: ${default.tootAttempts})."
      )
      val baseURI: Opt[String] = opt("base-uri", short = 'b', default = Some(default.baseURI),
        descr = s"Mastodon instance base URI (default: ${default.baseURI})."
      )
      val accountId: Opt[Id] = opt("account", short = 'a', default = Some(default.accountId),
        descr = s"Mastodon user id (default: ${default.accountId})."
      )

      verify()
      val config: Config = Config(
        username        = username(),
        password        = password(),
        initDelay       = initDelay(),
        preCropLeft     = preCropLeft(),
        preCropRight    = preCropRight(),
        shutterMorning  = shutterMorning(),
        shutterDay      = shutterDay(),
        shutterEvening  = shutterEvening(),
        shutterNight    = shutterNight(),
        pumpTimeOut     = pumpTimeOut(),
        verbose         = verbose(),
        pump            = !noPump(),
        toot            = !noToot(),
        shutdown        = !noShutdown(),
        httpIdleTimeOut = httpIdleTimeOut(),
        httpConnTimeOut = httpConnTimeOut(),
        tootAttempts    = tootAttempts(),
        baseURI         = baseURI(),
        accountId       = accountId(),
      )
    }
    run()(p.config)
  }

  def run()(implicit c: Config): Unit = {
    println(PiRun.nameAndVersion)

    // cf. https://github.com/Sciss/scaladon/issues/1
    sys.props("akka.http.client.idle-timeout")        = s"${c.httpIdleTimeOut}s"
    sys.props("akka.http.client.connecting-timeout")  = s"${c.httpConnTimeOut}s"

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
    val dateStr   = odt.withNano(0).toString
    val date      = Date.from(odt.toInstant)
    val isMorning = odt.getHour > 4  && odt.getHour <= 8
    val isDay     = odt.getHour > 8  && odt.getHour <= 16
    val isEvening = odt.getHour > 16 && odt.getHour <= 20
    println(s"Making photo (isDay? $isDay; isMorning? $isMorning)...")
    val shutter   =
      if      (isDay    ) c.shutterDay
      else if (isMorning) c.shutterMorning
      else if (isEvening) c.shutterEvening
      else                c.shutterNight
    val dirPhoto  = new File("photos").getCanonicalFile
    val fPhoto    = stampedFile(dirPhoto, pre = "snap", ext = "jpg", date = date)

    def logFailure(what: String, ex: Throwable): Unit =
      try {
        Console.err.println(s"${what.capitalize} failed:")
        ex.printStackTrace()
        val n  = s"log-${dateStr.replace(":", "_")}-$what.txt"
        val ps = new PrintStream(file(n), "UTF-8")
        try {
          ex.printStackTrace(ps)
        } finally {
          ps.close()
        }
      } catch {
        case _: Throwable => ()
      }

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
        logFailure("photo", ex)
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
          logFailure("crop", ex)
          false
      }

      if (hasCrop && c.toot) {
        println("Tooting photo...")
        val dateZero  = OffsetDateTime.parse("2021-06-19T04:00:00+02:00") // begin on June 19th, 2021
        val dayIdx    = Duration.between(dateZero, odt).toDays.toInt
        implicit val as: ActorSystem = ActorSystem()
        val futLogin  = Login(write = true)
        implicit val cfgAnn: Annotations.Config = Annotations.Config(
          verbose   = c.verbose,
          accountId = c.accountId,
          dayIdx    = dayIdx,
        )
        val futAnn  = futLogin.flatMap { login => Annotations.obtain(login) }
        val trAnn   = Try { Await.result(futAnn, SDuration(30, TimeUnit.SECONDS)) }
        val ann     = trAnn match {
          case Success(s) => s
          case Failure(ex) =>
            logFailure("annot", ex)
            "we're still trying to make contact..."
        }

        val text      = s"The time is $dateStr - $ann"
        val cToot = TootPhoto.Config(
          imgFile   = fPhotoCrop,
          text      = text,
        )

        @tailrec
        def attemptToot(attempt: Int): Unit = {
          if (attempt > 1) println(s"Attempt no. $attempt...")
          val futToot = futLogin.flatMap { login => TootPhoto(login)(cToot) }
          val trToot  = Try { Await.ready(futToot, SDuration(60, TimeUnit.SECONDS)) }
          val resToot: Try[Status] = trToot.flatMap { _ => futToot.value.get }

          resToot match {
            case Success(_) =>
              if (c.verbose) {
                println("Successfully sent toot.")
              }
            case Failure(ex) =>
              logFailure("toot", ex)
              if (attempt < c.tootAttempts) attemptToot(attempt + 1)
          }
        }

        attemptToot(attempt = 1)
      }
    }

    println("Done.")
    if (c.shutdown) {
//      if (c.verbose) {
        println("Shutting down...")
        Thread.sleep(8000L)
//      }
      shutdown()
    } else {
      sys.exit()
    }
  }

  def stampedFile(folder: File, pre: String, ext: String, date: Date): File = {
    val extP = if (ext.startsWith(".")) ext else s".$ext"
    require(!pre.contains('\''), "Invalid characters " + pre)
    require(!ext.contains('\''), "Invalid characters " + ext)
    val df = new SimpleDateFormat(s"'$pre-'yyMMdd'_'HHmmss'$extP'", Locale.US)
    new File(folder, df.format(date))
  }
}
