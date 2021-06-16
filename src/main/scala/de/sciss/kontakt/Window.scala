/*
 *  Window.scala
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
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.IOResult
import akka.stream.scaladsl.FileIO
import de.sciss.scaladon.Mastodon.Scope
import de.sciss.scaladon.{Attachment, AttachmentType, Id, Mastodon, Status, Visibility}
import net.harawata.appdirs.AppDirsFactory
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.awt.Image
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.swing.{Alignment, Label, MainFrame, Swing}

object Window {
  case class Config(
                     username   : String          = "user",
                     password   : String          = "pass",
                     verbose    : Boolean         = false,
                     baseURI    : String          = "botsin.space",
                     accountId  : Id              = Id("304274"),
//                     sinceId    : Option[Id]      = None,
                   )

  case class Data(
                 content  : String,
                 createdAt: ZonedDateTime,
                 imageUrl : String,
                 ) {
    override def toString: String =
      s"""$productPrefix(
         |  content = $content,
         |  createdAt = $createdAt,
         |  imageUrl = $imageUrl
         |)""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "Window"

      private val default = Config()

      implicit val idConverter: ValueConverter[Id] =
        singleArgConverter(new Id(_), PartialFunction.empty)  // Note: important to provide default arg (Dotty)

      val verbose: Opt[Boolean] = opt("verbose", short = 'V',
        descr = "Verbose printing."
      )
      val username: Opt[String] = opt("user", short = 'u', required = true,
        descr = "Mastodon bot user name."
      )
      val password: Opt[String] = opt("pass", short = 'p', required = true,
        descr = "Mastodon bot password."
      )
      val baseURI: Opt[String] = opt("base-uri", short = 'b', default = Some(default.baseURI),
        descr = s"Mastodon instance base URI (default: ${default.baseURI})."
      )
      val accountId: Opt[Id] = opt("account", short = 'a', default = Some(default.accountId),
        descr = s"Mastodon user id (default: ${default.accountId})."
      )
//      val sinceId: Opt[Id] = opt("since", short = 's',
//        descr = "Status id minimum to fetch."
//      )

      verify()
      val config: Config = Config(
        username  = username(),
        password  = password(),
        verbose   = verbose(),
        baseURI   = baseURI(),
        accountId = accountId(),
//        sinceId   = sinceId.toOption,
      )
    }

    run(p.config)
  }

  def run(config: Config): Unit = {
    implicit val as: ActorSystem = ActorSystem()
    ???
  }

  def appName   : String = "kontakt"
  def appAuthor : String = "de.sciss"

  /** Fetches a pair of data -- the most recent,
    * and the one a month earlier
    */
  def fetchPair(config: Config)(implicit as: ActorSystem): Unit = {
    import config._

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir(appName, /* version */ null, /* author */ appAuthor)
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)

    val statusFut = for {
      app   <- Mastodon.createApp(baseURI = baseURI, clientName = "kontakt_tooter",
        scopes = Set(Scope.Read), storageLoc = configBase)
      token <- app.login(username = username, password = password)
      res   <- app.Accounts.fetchStatuses(accountId, sinceId = Some(Id("106415590406823008")),
        onlyMedia = true, excludeReplies = true)(token)
    } yield {
      res
    }

    val statuses = Await.result(statusFut, Duration(4, TimeUnit.MINUTES))
    val data = statuses.collect {
      case Status(
        _ /*id*/,
        _ /*uri*/,
        _ /*url*/,
        _ /*account*/,
        None /*inReplyToId*/,
        None /*inReplyToAccountId*/,
        _ /*reblog*/,
        content,
        createdAt,
        _ /*reblogsCount*/,
        _ /*favouritesCount*/,
        _ /*reblogged*/,
        _ /*favourited*/,
        _ /*sensitive*/,
        _ /*spoilerText*/,
        Visibility.Public,
        Attachment(
          _ /*id*/,
          AttachmentType.Image,
          imageUrl,
          _ /*remoteUrl*/,
          _ /*previewUrl*/,
          _ /*textUrl*/,
      ) +: _,
        _ /*mentions*/,
        _ /*tags*/,
        _ /*application*/,
      ) => Data(content = content, createdAt = createdAt, imageUrl = imageUrl)
    }
    println(s"Found ${statuses.size} statuses, ${data.size} of which are usable data.\n")
    data.foreach(println)

    data.headOption.foreach { data =>
      val f         = File.createTempFile("tmp", ".jpg")
      val futFile   = downloadFile(data.imageUrl, f)
      /*val ioRes =*/ Await.result(futFile, Duration(4, TimeUnit.MINUTES))
      // println(s"ioRes = $ioRes")
      val imgOrig   = ImageIO.read(f)
      val img       = imgOrig.getScaledInstance(960, 960, Image.SCALE_SMOOTH)
      f.delete()
      Swing.onEDT {
        new MainFrame {
          title = data.createdAt.toString
          contents = new Label(null, new ImageIcon(img), Alignment.Leading)
          pack().centerOnScreen()
          open()
        }
      }
    }
  }

  def downloadFile(uri: String, outputFile: File)(implicit as: ActorSystem): Future[IOResult] = {
    val req     = HttpRequest(uri = uri)
    val reqFut = Http().singleRequest(req)

    reqFut.flatMap { response =>
      val source = response.entity.dataBytes
      source.runWith(FileIO.toFile(outputFile))
    }
  }
}
