package de.sciss.kontakt

import akka.actor.ActorSystem
import de.sciss.file._
import de.sciss.kontakt.Common.{idLong, longId}
import de.sciss.kontakt.Login.{appAuthor, appName}
import de.sciss.scaladon.{Account, Conversation, Id, Status, Visibility}
import de.sciss.serial.{ConstFormat, DataInput, DataOutput}
import net.harawata.appdirs.AppDirsFactory
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.time.{OffsetDateTime, ZonedDateTime, Duration => JDuration}
import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, blocking}
import scala.util.{Failure, Success}

object Annotations {
  case class FullConfig(
                     username : String  = "user",
                     password : String  = "pass",
                     baseURI  : String  = "botsin.space",
                     verbose  : Boolean = false,
                     accountId: Id      = Id("304274"),
                     wipeApp  : Boolean = false,
                     wipeCache: Boolean = false,
                     dayIdx   : Int     = -1,
                   ) extends ConfigBase with Login.Config

  trait ConfigBase {
    def verbose  : Boolean
    val accountId: Id
    def dayIdx   : Int
  }

  case class Config(
    verbose  : Boolean = false,
    accountId: Id      = Id("304274"),
    dayIdx   : Int
  ) extends ConfigBase

  val DAYS_IN_RESOURCES = 172

  def defaultDayIndex(odt: OffsetDateTime = OffsetDateTime.now()): Int = {
    val dateZero  = OffsetDateTime.parse("2021-06-19T04:00:00+02:00") // begin on June 19th, 2021
    val dayIdx    = JDuration.between(dateZero, odt).toDays.toInt
    dayIdx
  }

  def main(args: Array[String]): Unit = {
//    val TEST = Await.result(PiRun.obtainAnnotation(null, DAYS_IN_RESOURCES - 1), Duration.Inf)
//    println(TEST)
//    sys.exit()

    object p extends ScallopConf(args) {
      printedName = "Annotations"
      private val default = FullConfig()

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
      val wipeApp: Opt[Boolean] = opt("wipe-app",
        descr = "Wipe Mastodon app data."
      )
      val wipeCache: Opt[Boolean] = opt("wipe-cache",
        descr = "Wipe Mastodon annotation cache."
      )
      val dayIdx: Opt[Int] = opt("day-index", short = 'd',
        descr = "Override automatic day index."
      )

      verify()
      val config: FullConfig = FullConfig(
        username        = username(),
        password        = password(),
        verbose         = verbose(),
        baseURI         = baseURI(),
        accountId       = accountId(),
        wipeApp         = wipeApp(),
        wipeCache       = wipeCache(),
        dayIdx          = dayIdx.getOrElse(defaultDayIndex()),
      )
    }
    val fut = run()(p.config)
    Await.ready(fut, Duration.Inf)
    fut.value.get match {
      case Success(ann) =>
        println("Successful.")
        println(ann)
        sys.exit()

      case Failure(ex) =>
        println("Failed:")
        ex.printStackTrace()
        sys.exit(1)
    }
  }

  def log(what: => String)(implicit config: ConfigBase): Unit =
    if (config.verbose) println(s"[${new Date}] kontakt - $what")

  object Entries {
    implicit object ser extends ConstFormat[Entries] {
      private final val COOKIE = 0x416E6E6F

      override def write(v: Entries, out: DataOutput): Unit = {
        out.writeInt(COOKIE)
        import v._
        out.writeInt(seq.size)
        seq.foreach(Entry.ser.write(_, out))
      }

      override def read(in: DataInput): Entries = {
        require (in.readInt() == COOKIE)
        val sz = in.readInt()
        val seq = Seq.fill(sz)(Entry.ser.read(in))
        Entries(seq)
      }
    }
  }
  case class Entries(seq: Seq[Entry]) {
    def size: Int = seq.size

    def isEmpty: Boolean = seq.isEmpty

    def headOption: Option[Entry] = seq.headOption
    def lastOption: Option[Entry] = seq.lastOption

    def insert(that: Seq[Entry]): Entries = {
      if (that.isEmpty) this else if (this.isEmpty) Entries(that) else {
        // require (isStrictlyMonotonous(that), "Inserting an unsorted batch")
        val newSeq = (that ++ this.seq).sorted.distinct
        copy (seq = newSeq)
      }
    }
  }

  object Entry {
    implicit object ser extends ConstFormat[Entry] {
      private final val COOKIE = 0x4461

      override def write(v: Entry, out: DataOutput): Unit = {
        out.writeShort(COOKIE)
        import v._
        out.writeLong(idL)
        out.writeUTF(content)
        out.writeUTF(createdAt.toString)
      }

      override def read(in: DataInput): Entry = {
        require (in.readShort() == COOKIE)
        val id        = in.readLong()
        val content   = in.readUTF()
        val createdAt = ZonedDateTime.parse(in.readUTF)
        Entry(idL = id, content = content, createdAt = createdAt)
      }
    }

    // implicit val byNewest: Ordering[Entry] = Ordering.by[Entry, Long](_.idL).reverse
    implicit val byOldest: Ordering[Entry] = Ordering.by[Entry, Long](_.idL)
  }
  case class Entry(
                    idL        : Long,
                    content    : String,
                    createdAt  : ZonedDateTime,
                  ) {
    override def toString: String =
      s"""$productPrefix(
         |  id        = $idL,
         |  content   = $content,
         |  createdAt = $createdAt,
         |)""".stripMargin

    def id: Id = idLong(idL)
  }

  def obtain(login: Login)(implicit config: ConfigBase): Future[String] = {
    import config.dayIdx
    val dayIdxOff = dayIdx - DAYS_IN_RESOURCES
    if (dayIdxOff < 0)
      Future {
        val st = getClass.getResourceAsStream("/annotations.txt")
        io.Source.fromInputStream(st, "UTF-8").getLines().drop(dayIdx).next()
      }
    else {
      updateEntries(login).map { entries =>
        val entry = entries.seq(dayIdxOff)
        Common.htmlToPlain(entry.content)
      }
    }
  }

  private def cacheFile(): File = {
    val appDirs     = AppDirsFactory.getInstance
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)
    file(cacheBase) / "annot.bin"
  }

  private val writeLock = new AnyRef

  def readCache(): Future[Entries] = {
    val f = cacheFile()
    if (f.isFile) {
      Future {
        blocking {
          val dIn = DataInput.open(f)
          try {
            Entries.ser.read(dIn)
          } finally {
            dIn.close()
          }
        }
      }

    } else {
      Future.successful(Entries(Nil))
    }
  }

  def writeCache(e: Entries): Future[Unit] = {
    val f = cacheFile()
    Future {
      blocking {
        writeLock.synchronized {
          f.parent.mkdirs()
          val dOut = DataOutput.open(f)
          try {
            Entries.ser.write(e, dOut)
          } finally {
            dOut.close()
          }
        }
      }
    }
  }

  def updateEntries(login: Login)(implicit config: ConfigBase): Future[Entries] = {
    import config._
    import login._

    // grabs all updates
    def updateLoop(oldEntries: Entries, sinceId: Option[Id], maxId: Option[Id]): Future[Entries] = {
      log(s"Fetching new annotations since $sinceId up until $maxId")
      val limit = 20  // Mastodon API constraint
      val conversationsFut = app.Conversations.fetch(sinceId = sinceId, maxId = maxId, limit = limit)
      conversationsFut.flatMap { conversations =>
        log(s"Received ${conversations.size} new conversations")
        val newStatuses = conversations.collect {
          case Conversation(
            _ /*id*/,
            _ /*accounts*/,
            _ /*unread*/,
            Some(status)
          ) => status
        }
        val newEntries0 = newStatuses.collect {
          case Status(
            statusId /*id*/,
            _ /*uri*/ ,
            _ /*url*/ ,
            Account(
              `accountId`,
              _ /*username*/,
              _ /*acct*/,
              _ /*displayName*/,
              _ /*note*/,
              _ /*url*/,
              _ /*avatar*/,
              _ /*header*/,
              _ /*locked*/,
              _ /*createdAt*/,
              _ /*followersCount*/,
              _ /*followingCount*/,
              _ /*statusesCount*/,
            ),
            None /*inReplyToId*/ ,
            None /*inReplyToAccountId*/ ,
            _ /*reblog*/ ,
            content,
            createdAt,
            _ /*reblogsCount*/ ,
            _ /*favouritesCount*/ ,
            _ /*reblogged*/ ,
            _ /*favourited*/ ,
            _ /*sensitive*/ ,
            Some("annot") /*spoilerText*/ ,
            Visibility.Direct,
            _ /*mediaAttachments*/,
            _ /*mentions*/ ,
            _ /*tags*/ ,
            _ /*application*/ ,
          ) => Entry(idL = longId(statusId), content = content, createdAt = createdAt)
        }
        log(s"Yielding ${newStatuses.size} new statuses and ${newEntries0.size} new entries")

        if (newStatuses.isEmpty) Future.successful(oldEntries)
        else {
          if (newEntries0.nonEmpty) {
            log(s"Ranging from ${newEntries0.head.createdAt} to ${newEntries0.last.createdAt}")
          }
          val newEntries  = newEntries0.sorted  // oldest till newest
          val combined    = oldEntries.insert(newEntries)
          if (maxId /*sinceId*/.isDefined && newStatuses.size < limit) {
            Future.successful(combined)
          } else {
            val newSinceId = idLong(newStatuses.map(status => longId(status.id)).max)
            val newMaxId   = idLong(newStatuses.map(status => longId(status.id)).min)
            updateLoop(combined, sinceId = sinceId /*Some(newSinceId)*/, maxId = Some(newMaxId) /*maxId*/)
          }
        }
      }
    }

    val oldCacheFut = readCache()
    oldCacheFut.flatMap { oldCache =>
      log(s"Read cache with ${oldCache.size} entries")
      val sinceId = oldCache.lastOption.map(_.id)
      val combinedCacheFut = updateLoop(oldCache, sinceId = sinceId, maxId = None)
      combinedCacheFut.flatMap { newCache =>
        if (oldCache == newCache || newCache.isEmpty) {
          log("No change in entries")
          Future.successful(newCache)
        } else {
          log(s"New size is ${newCache.size}")
          writeCache(newCache).map { _ =>
            log("Wrote cache")
            newCache
          }
        }
      }
    }
  }

  def run()(implicit config: FullConfig): Future[String] = {
    import config._
    if (wipeCache) {
      if (!cacheFile().delete()) {
        println("Could not wipe cache file")
      }
    }
    implicit val as: ActorSystem = ActorSystem()
    for {
      login <- Login(write = true, wipe = wipeApp)
      res <- {
        obtain(login)
      }
    } yield res
  }
}
