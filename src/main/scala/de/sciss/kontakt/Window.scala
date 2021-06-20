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
import de.sciss.file._
import de.sciss.scaladon.Mastodon.Scope
import de.sciss.scaladon.{AccessToken, Attachment, AttachmentType, Id, Mastodon, Status, Visibility}
import de.sciss.serial.{ConstFormat, DataInput, DataOutput}
import net.harawata.appdirs.AppDirsFactory
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.image.BufferedImage
import java.awt.{Color, EventQueue, RenderingHints, Toolkit}
import java.time.ZonedDateTime
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.{AbstractAction, JComponent, KeyStroke, SwingUtilities}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.math.{max, min}
import scala.swing.{BoxPanel, Component, Dimension, Graphics2D, MainFrame, Orientation, RootPanel, Swing}
import scala.util.{Failure, Success}

object Window {
  case class Config(
                     username   : String  = "user",
                     password   : String  = "pass",
                     verbose    : Boolean = false,
                     baseURI    : String  = "botsin.space",
                     accountId  : Id      = Id("304274"),
                     imgExtent  : Int     = 816,
                     panelWidth : Int     = 1920/2,
                     panelHeight: Int     = 1080,
                     hasView    : Boolean = true,
                     yShift     : Int     = 0,
                   )

  object Entry {
    implicit object ser extends ConstFormat[Entry] {
      private final val COOKIE = 0x4461

      override def write(v: Entry, out: DataOutput): Unit = {
        out.writeShort(COOKIE)
        import v._
        out.writeLong(idL)
        out.writeUTF(content)
        out.writeUTF(createdAt.toString)
        out.writeUTF(imagePath)
      }

      override def read(in: DataInput): Entry = {
        require (in.readShort() == COOKIE)
        val id        = in.readLong()
        val content   = in.readUTF()
        val createdAt = ZonedDateTime.parse(in.readUTF)
        val imagePath = in.readUTF()
        Entry(idL = id, content = content, createdAt = createdAt, imagePath = imagePath)
      }
    }

    implicit val byNewest: Ordering[Entry] = Ordering.by[Entry, Long](_.idL).reverse
  }
  case class Entry(
                    idL        : Long,
                    content    : String,
                    createdAt  : ZonedDateTime,
                    imagePath  : String,
                 ) {
    override def toString: String =
      s"""$productPrefix(
         |  id        = $idL,
         |  content   = $content,
         |  createdAt = $createdAt,
         |  imagePath = $imagePath
         |)""".stripMargin

    def id: Id = idLong(idL)
  }

  def main(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)

    object p extends ScallopConf(args) {
      printedName = "Window"

      private val default = Config()

      implicit val idConverter: ValueConverter[Id] =
        singleArgConverter(new Id(_), PartialFunction.empty)  // Note: important to provide default arg (Dotty)

      val verbose: Opt[Boolean] = opt("verbose", short = 'V', default = Some(default.verbose),
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
      val imgExtent: Opt[Int] = opt("extent", default = Some(default.imgExtent),
        descr = s"Single image side length in pixels (default: ${default.imgExtent}).",
        validate = x => x >= 16 && x <= 1280
      )
      val yShift: Opt[Int] = opt("y-shift", default = Some(default.yShift),
        descr = s"Vertical shift in pixels (default: ${default.yShift}).",
      )
      val noView: Opt[Boolean] = opt("no-view", default = Some(!default.hasView), descr = "Do not open window.")

      verify()
      val config: Config = Config(
        username  = username(),
        password  = password(),
        verbose   = verbose(),
        baseURI   = baseURI(),
        accountId = accountId(),
        imgExtent = imgExtent(),
        hasView   = !noView(),
        yShift    = yShift(),
      )
    }

    run()(p.config)
  }

  def run()(implicit config: Config): Unit = {
    implicit val as: ActorSystem = ActorSystem()
    var view = Option.empty[View]
    if (config.hasView) Swing.onEDT {
      view = Some(openView())
    }

    val futAll = for {
      li <- login()
      entries <- {
        implicit val _li: Login = li
        updateEntries()
      }
      (cOld, cNew) <- fetchPair(entries)
    } yield {
      if (config.hasView) Swing.onEDT {
        view.foreach { v =>
          v.left  = Some(cOld)
          v.right = Some(cNew)
        }
      }
      ()
    }

    futAll.onComplete {
      case Success(_) => log("Updates completed")
      case Failure(ex) =>
        Console.err.println("Update failed:")
        ex.printStackTrace()
    }
  }

  def appName   : String = "kontakt"
  def appAuthor : String = "de.sciss"

  private def longId(x: Id): Long =
    try {
      x.value.toLong
    } catch {
      case _: Exception => -1L
    }

  private def idLong(x: Long): Id = Id(x.toString)

  class Login(val app: Mastodon,
              implicit val token: AccessToken,
              implicit val actorSystem: ActorSystem
             )

  def login()(implicit config: Config, as: ActorSystem): Future[Login] = {
    import config._

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir(appName, /* version */ null, /* author */ appAuthor)

    for {
      app <- Mastodon.createApp(baseURI = baseURI, clientName = "kontakt_tooter",
        scopes = Set(Scope.Read), storageLoc = configBase)
      token <- app.login(username = username, password = password)
    } yield
      new Login(app, token, as)
  }

  object Entries {
    def empty: Entries = Entries(Nil)

    implicit object ser extends ConstFormat[Entries] {
      private final val COOKIE = 0x4361

      override def write(v: Entries, out: DataOutput): Unit = {
        out.writeShort(COOKIE)
        import v._
        out.writeInt(seq.size)
        seq.foreach(Entry.ser.write(_, out))
      }

      override def read(in: DataInput): Entries = {
        require (in.readShort() == COOKIE)
        val sz = in.readInt()
        val seq = Seq.fill(sz)(Entry.ser.read(in))
        Entries(seq)
      }
    }
  }
  case class Entries(seq: Seq[Entry]) {
    def isEmpty : Boolean = seq.isEmpty
    def nonEmpty: Boolean = seq.nonEmpty

    def size: Int = seq.size

    def headOption: Option[Entry] = seq.headOption
    def lastOption: Option[Entry] = seq.lastOption

    def head: Entry = seq.head
    def last: Entry = seq.last

//    def prepend(that: Seq[Entry]): Entries = {
//      require (this.isEmpty || that.isEmpty || {
//        val a = this.head
//        val b = that.last
//        (a.createdAt isBefore b.createdAt) && (a.idL < b.idL)
//      }, s"${this.headOption} not before ${that.lastOption}")
//      require (that == that.sorted, "New entries are not sorted")
//      copy (seq = that ++ this.seq)
//    }

    def insert(that: Seq[Entry]): Entries = {
      if (that.isEmpty) this else if (this.isEmpty) Entries(that) else {
        // require (isStrictlyMonotonous(that), "Inserting an unsorted batch")
        val newSeq = (that ++ this.seq).sorted.distinct
        copy (seq = newSeq)
      }
    }

    def take(n: Int): Entries = copy (seq = seq.take(n))
  }

//  def isStrictlyMonotonous[A](seq: Seq[A])(implicit ordering: Ordering[A]): Boolean = {
//    seq.sliding(2).forall {
//      case Seq(a, b) => ordering.lt(a, b)
//      case _ => true
//    }
//  }

  private def cacheFile(): File = {
    val appDirs     = AppDirsFactory.getInstance
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)
    file(cacheBase) / "statuses.bin"
  }

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
      Future.successful(Entries.empty)
    }
  }

  def writeCache(e: Entries): Future[Unit] = {
    val f = cacheFile()
    Future {
      blocking {
        val dOut = DataOutput.open(f)
        try {
          Entries.ser.write(e, dOut)
        } finally {
          dOut.close()
        }
      }
    }
  }

  def log(what: => String)(implicit config: Config): Unit =
    if (config.verbose) println(s"[log] $what")

  case class Content(
                    text: String,
                    image: BufferedImage,
                    )

  // note: also fails if entries is empty
  def fetchPair(entries: Entries) /*(implicit config: Config)*/: Future[(Content, Content)] =
    Future {
      def readOne(e: Entry): Content = {
        val f = localImageFile(e)
        val imgOrig = blocking {
          ImageIO.read(f)
        }
//        val img     = imgOrig.getScaledInstance(config.imgExtent, config.imgExtent, Image.SCALE_SMOOTH)
        val sep     = " - "
        val sepIdx  = e.content.indexOf(sep)
        val text    = if (sepIdx < 0) e.content else e.content.substring(sepIdx + sep.length)
        Content(text, imgOrig)
      }

      (readOne(entries.last), readOne(entries.head))
    }

  // all methods must be called on EDT
  trait View {
    var left  : Option[Content]
    var right : Option[Content]
  }

  def openView()(implicit config: Config): View = {
    require (EventQueue.isDispatchThread)
    new ViewImpl
  }

  private final class ViewImpl(implicit config: Config) extends MainFrame with View {
    peer.setUndecorated(true)

//    title = "Kontakt"

    private def mkSideView(isLeft: Boolean) =
      new ViewSideImpl(config.panelWidth, config.panelHeight, yShift = config.yShift,
        imgExtent = config.imgExtent, alignRight = isLeft)

    private val leftView  = mkSideView(isLeft = true  )
    private val rightView = mkSideView(isLeft = false )

    private val hiddenCursor: java.awt.Cursor =
      java.awt.Toolkit.getDefaultToolkit.createCustomCursor(
        new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), new java.awt.Point(0, 0), "hidden")

    leftView  .cursor = hiddenCursor
    rightView .cursor = hiddenCursor

    contents = new BoxPanel(Orientation.Horizontal) {
      contents += leftView
      contents += rightView
    }

//    peer.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH)
    pack()
    open()
    toggleFullScreen(this)

    // XXX TODO: doesn't work:
//    installFullScreenKey(this, leftView)
//    leftView.requestFocus()

    def left: Option[Content] = leftView.data
    def left_=(c: Option[Content]): Unit = leftView.data_=(c)

    def right: Option[Content] = rightView.data
    def right_=(c: Option[Content]): Unit = rightView.data_=(c)
  }

  def toggleFullScreen(frame: RootPanel): Unit = {
    val gc = frame.peer.getGraphicsConfiguration
    val sd = gc.getDevice
    val w  = SwingUtilities.getWindowAncestor(frame.peer.getRootPane)
    sd.setFullScreenWindow(if (sd.getFullScreenWindow == w) null else w)
  }

  def installFullScreenKey(frame: RootPanel, display: Component): Unit = {
    val iMap    = display.peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    val aMap    = display.peer.getActionMap
    val fsName  = "fullscreen"
    iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit.getMenuShortcutKeyMask |
      InputEvent.SHIFT_MASK), fsName)
    aMap.put(fsName, new AbstractAction(fsName) {
      def actionPerformed(e: ActionEvent): Unit = {
        println("toggleFullScreen")
        toggleFullScreen(frame)
      }
    })
  }

  private final class ViewSideImpl(_width: Int, _height: Int, yShift: Int, imgExtent: Int, alignRight: Boolean)
    extends Component {

    opaque        = true
    preferredSize = new Dimension(_width, _height)

    private var _data = Option.empty[Content]

    def data: Option[Content] = _data
    def data_=(c: Option[Content]): Unit = {
      _data = c
      repaint()
    }

    private val imgX = if (alignRight) _width - imgExtent else 0
    private val pMin = min(_width, _height)
    private val pMax = max(_width, _height)
    private val imgY = (pMin - imgExtent)/2 + (pMax - pMin)

//    listenTo(mouse.clicks)

    override protected def paintComponent(g: Graphics2D): Unit = {
      super.paintComponent(g)
      g.setColor(Color.black)
      g.fillRect(0, 0, _width, _height)
      _data.foreach { c =>
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION , RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING     , RenderingHints.VALUE_RENDER_QUALITY       )
        g.drawImage(c.image, imgX, imgY, imgExtent, imgExtent, peer)
      }
    }
  }

  def photoDir: File = {
    val appDirs     = AppDirsFactory.getInstance
    val cacheBase   = appDirs.getUserCacheDir (appName, /* version */ null, /* author */ appAuthor)
    file(cacheBase) / "photos"
  }

  def localImageFile(e: Entry): File =
    photoDir / s"${e.id.value}.jpg"

  def truncateEntries(in: Entries): Entries = if (in.isEmpty) in else {
    val newest    = in.head.createdAt
    val thresh    = newest.minusMonths(1).minusHours(2)
    val valid     = in.seq.segmentLength(_.createdAt.isAfter(thresh))
    in.take(valid)
  }

  def updateEntries()(implicit config: Config, li: Login): Future[Entries] = {
    import config._
    import li._

    photoDir.mkdirs()

    // grabs all updates and truncates
    def updateLoop(oldEntries: Entries, sinceId: Option[Id], maxId: Option[Id]): Future[Entries] = {
      // XXX TODO: This is some Akka sh*t - 32 max number of queued requests
      // https://doc.akka.io/docs/akka-http/current/client-side/pool-overflow.html?language=scala
      // I'm too lazy to figure what the f*ck the designers want me to do
      val limit = 30 // Mastodon.DEFAULT_LIMIT
      log(s"Fetching new statuses since $sinceId up until $maxId")
      val statusFut = app.Accounts.fetchStatuses(accountId, sinceId = sinceId, maxId = maxId, limit = limit,
        onlyMedia = true, excludeReplies = true)
      statusFut.flatMap { statuses =>
        log(s"Received ${statuses.size} new statuses")
        val newEntries0 = statuses.collect {
          case Status(
          statusId,
          _ /*uri*/ ,
          _ /*url*/ ,
          _ /*account*/ ,
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
          _ /*spoilerText*/ ,
          Visibility.Public,
          Attachment(
          _ /*id*/ ,
          AttachmentType.Image,
          imageUrl,
          _ /*remoteUrl*/ ,
          _ /*previewUrl*/ ,
          _ /*textUrl*/ ,
          ) +: _,
          _ /*mentions*/ ,
          _ /*tags*/ ,
          _ /*application*/ ,
          ) => Entry(idL = longId(statusId), content = content, createdAt = createdAt, imagePath = imageUrl)
        }
        log(s"Yielding ${newEntries0.size} new entries")

        if (newEntries0.isEmpty) Future.successful(truncateEntries(oldEntries))
        else {
          log(s"Ranging from ${newEntries0.head.createdAt} to ${newEntries0.last.createdAt}")
          val newEntries  = newEntries0.sorted
          val imagesFutSq = newEntries.map { e =>
            val f = localImageFile(e)
            downloadFile(e.imagePath, f)
          }
          val imagesFut = Future.sequence(imagesFutSq)
          imagesFut.flatMap { _ =>
            log("Downloaded the corresponding photos")
            val combined  = oldEntries.insert(newEntries)
            val trunc     = truncateEntries(combined)
            if ((sinceId.isDefined && statuses.size < limit) || (sinceId.isEmpty && trunc.size < combined.size)) {
              Future.successful(trunc)
            } else {
              val maxId = newEntries.lastOption.map(_.id)
              updateLoop(combined, sinceId = sinceId, maxId = maxId)
            }
          }
        }
      }
    }

    val oldCacheFut = readCache()
    oldCacheFut.flatMap { oldCache =>
      log(s"Read cache with ${oldCache.size} entries")
      val sinceId = oldCache.headOption.map(_.id)
      val combinedCacheFut = updateLoop(oldCache, sinceId = sinceId, maxId = None)
      combinedCacheFut.flatMap { newCache =>
        if (oldCache == newCache || newCache.isEmpty) {
          log("No change in entries")
          Future.successful(newCache)
        } else {
          log(s"Truncated size is ${newCache.size}")
          writeCache(newCache).map { _ =>
            log("Wrote cache")
            newCache
          }
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
