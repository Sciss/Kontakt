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
import de.sciss.scaladon.{AccessToken, Attachment, AttachmentType, Id, Mastodon, Status, Visibility}
import de.sciss.serial.{ConstFormat, DataInput, DataOutput, Format}
import net.harawata.appdirs.AppDirsFactory
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.awt.Image
import de.sciss.file._
import java.time.ZonedDateTime
import java.util.Locale
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

  object Data {
    implicit object ser extends ConstFormat[Data] {
      private final val COOKIE = 0x4461

      override def write(v: Data, out: DataOutput): Unit = {
        out.writeShort(COOKIE)
        import v._
        out.writeLong(id)
        out.writeUTF(content)
        out.writeUTF(createdAt.toString)
        out.writeUTF(imagePath)
      }

      override def read(in: DataInput): Data = {
        require (in.readShort() == COOKIE)
        val id        = in.readLong()
        val content   = in.readUTF()
        val createdAt = ZonedDateTime.parse(in.readUTF)
        val imagePath = in.readUTF()
        Data(id = id, content = content, createdAt = createdAt, imagePath = imagePath)
      }
    }
  }
  case class Data(
                   id         : Long,
                   content    : String,
                   createdAt  : ZonedDateTime,
                   imagePath  : String,
                 ) {
    override def toString: String =
      s"""$productPrefix(
         |  id        = $id,
         |  content   = $content,
         |  createdAt = $createdAt,
         |  imagePath = $imagePath
         |)""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

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

  private def longId(x: Id): Long =
    try {
      x.value.toLong
    } catch {
      case _: Exception => -1L
    }

  class Login(val app: Mastodon,
              implicit val token: AccessToken,
              implicit val actorSystem: ActorSystem
             )

  def login()(config: Config)(implicit as: ActorSystem): Future[Login] = {
    import config._

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir(appName, /* version */ null, /* author */ appAuthor)

    for {
      app   <- Mastodon.createApp(baseURI = baseURI, clientName = "kontakt_tooter",
        scopes = Set(Scope.Read), storageLoc = configBase)
      token <- app.login(username = username, password = password)
    } yield
      new Login(app, token, as)
  }

  object Cache {
    def empty: Cache = Cache(Nil)

    implicit object ser extends ConstFormat[Cache] {
      private final val COOKIE = 0x4361

      override def write(v: Cache, out: DataOutput): Unit = {
        out.writeShort(COOKIE)
        import v._
        out.writeInt(seq.size)
        seq.foreach(Data.ser.write(_, out))
      }

      override def read(in: DataInput): Cache = {
        require (in.readShort() == COOKIE)
        val sz = in.readInt()
        val seq = Seq.fill(sz)(Data.ser.read(in))
        Cache(seq)
      }
    }
  }
  case class Cache(seq: Seq[Data])

  private def cacheFile(): File = {
    val appDirs     = AppDirsFactory.getInstance
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)
    file(cacheBase) / "statuses.bin"
  }

  def readCache(): Future[Cache] = {
    val f = cacheFile()
    if (f.isFile) {
      ???

    } else {
      Future.successful(Cache.empty)
    }
  }

  /** Fetches a pair of data -- the most recent,
    * and the one a month earlier
    */
  def fetchPair(config: Config)(implicit li: Login): Unit = {
    import config._
    import li._

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir(appName, /* version */ null, /* author */ appAuthor)
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)

    val statusFut = for {
      res <- app.Accounts.fetchStatuses(accountId, sinceId = Some(Id("106415590406823008")),
        onlyMedia = true, excludeReplies = true)(token)
    } yield {
      res
    }

    val statuses = Await.result(statusFut, Duration(4, TimeUnit.MINUTES))
    val data = statuses.collect {
      case Status(
        statusId,
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
      ) => Data(id = longId(statusId), content = content, createdAt = createdAt, imagePath = imageUrl)
    }
    println(s"Found ${statuses.size} statuses, ${data.size} of which are usable data.\n")
    data.foreach(println)

    data.headOption.foreach { data =>
      val f         = File.createTempFile("tmp", ".jpg")
      val futFile   = downloadFile(data.imagePath, f)
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

  def downloadFile(uri: String, outputFile: File)(implicit li: Login): Future[IOResult] = {
    import li._
    val req     = HttpRequest(uri = uri)
    val reqFut = Http().singleRequest(req)

    reqFut.flatMap { response =>
      val source = response.entity.dataBytes
      source.runWith(FileIO.toFile(outputFile))
    }
  }
}
