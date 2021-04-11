/*
 *  TootPhoto.scala
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

import akka.actor.ActorSystem
import de.sciss.scaladon.Mastodon.Scope
import de.sciss.scaladon.{Mastodon, Status, Visibility}
import net.harawata.appdirs.AppDirsFactory
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

object TootPhoto {
  case class Config(
                     username   : String          = "user",
                     password   : String          = "pass",
                     imgFile    : File            = new File("out.jpg"),
                     text       : String          = "Still trying to make contact...",
                     imgDescr   : String          = "Photograph of a glass plate with lichen fragments",
                     spoiler    : Option[String]  = None,
                     verbose    : Boolean         = false,
                     baseURI    : String          = "botsin.space"
                   )

  def main(args: Array[String]): Unit = {

    object p extends ScallopConf(args) {
      printedName = "CropPhoto"
      //      version(fullName)
      private val default = Config()

      val imgFile: Opt[File] = opt("input", short = 'i', required = true,
        descr = "Processed photo file to be tooted."
      )
      val verbose: Opt[Boolean] = opt("verbose", short = 'V',
        descr = "Verbose printing."
      )
      val username: Opt[String] = opt("user", short = 'u', required = true,
        descr = "Mastodon bot user name."
      )
      val password: Opt[String] = opt("pass", short = 'p', required = true,
        descr = "Mastodon bot password."
      )
      val imgDescr: Opt[String] = opt("image-description", short = 'd', default = Some(default.imgDescr),
        descr = s"Image description for accessibility (default: ${default.imgDescr})."
      )
      val spoiler: Opt[String] = opt("spoiler",
        descr = "Spoiler or CW text."
      )
      val baseURI: Opt[String] = opt("base-uri", short = 'b', default = Some(default.baseURI),
        descr = s"Mastodon instance base URI (default: ${default.baseURI})."
      )

      verify()
      val config: Config = Config(
        username        = username(),
        password        = password(),
        imgFile         = imgFile(),
        verbose         = verbose(),
        imgDescr        = imgDescr(),
        spoiler         = spoiler.toOption,
        baseURI         = baseURI(),
      )
    }
    val fut = run(p.config)
    Await.ready(fut, Duration.Inf)
    fut.value.get match {
      case Success(data) =>
        println("Successful.")
        println(data)
        sys.exit()

      case Failure(ex) =>
        println("Failed:")
        ex.printStackTrace()
        sys.exit(1)
    }
  }

  def run(config: Config): Future[Status] = {
    import config._

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir("kontakt", /* version */ null, /* author */ "de.sciss")
//    println(configBase)

//    println(Scope.all.mkString(" "))
//    sys.exit()

    implicit val as: ActorSystem = ActorSystem()

    val testFut = for {
      app   <- Mastodon.createApp(baseURI = baseURI, clientName = "kontakt_tooter",
        scopes = Set(Scope.Read, Scope.Write), storageLoc = configBase)
      token <- app.login(username = username, password = password)
      res   <- {
        val futAtt = app.Statuses.uploadMedia(
          imgFile,
          description = Some(imgDescr),
//          focus = Some(MediaFocus(1.0f, 0.0f))
        )(token)
        futAtt.flatMap { att =>
          app.Statuses.post(
            status      = text,
            visibility  = Visibility.Public,
            mediaIds    = att.id :: Nil,
            sensitive   = false,
            spoilerText = spoiler,
          )(token)
        }
      }
    } yield {
      res
    }

    testFut
  }
}
