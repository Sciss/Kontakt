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
import de.sciss.kontakt.Common.{fullVersion, shutdown}
import de.sciss.scaladon.Mastodon.Scope
import de.sciss.scaladon.{AccessToken, Attachment, AttachmentType, Id, Mastodon, Status, Visibility}
import de.sciss.serial.{ConstFormat, DataInput, DataOutput}
import net.harawata.appdirs.AppDirsFactory
import org.unbescape.html.HtmlEscape
//import org.apache.commons.text.StringEscapeUtils
import org.rogach.scallop.{ScallopConf, ValueConverter, singleArgConverter, ScallopOption => Opt}

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.font.{LineBreakMeasurer, TextAttribute}
import java.awt.image.BufferedImage
import java.awt.{Color, EventQueue, Font, RenderingHints, Toolkit}
import java.text.AttributedString
import java.time.temporal.ChronoUnit
import java.time.{OffsetDateTime, ZonedDateTime}
import java.util.{Date, Locale, Timer, TimerTask}
import javax.imageio.ImageIO
import javax.swing.{AbstractAction, JComponent, KeyStroke, SwingUtilities}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.math.{max, min}
import scala.swing.{BoxPanel, Component, Dimension, Graphics2D, MainFrame, Orientation, RootPanel, Swing}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/** This is the code for the physical installation at Reagenz and Glowing Globe.
  */
object Window {
  case class Config(
                     username     : String  = "user",
                     password     : String  = "pass",
                     verbose      : Boolean = false,
                     initDelay    : Int     = 120,
                     shutdownHour : Int     = 21,
                     shutdown     : Boolean = true,
                     baseURI      : String  = "botsin.space",
                     accountId    : Id      = Id("304274"),
                     imgExtent    : Int     = 816,
                     imgCrop      : Int     = 160,
                     panelWidth   : Int     = 1920/2,
                     panelHeight  : Int     = 1080,
                     hasView      : Boolean = true,
                     yShift       : Int     = 0,
                     skipUpdate   : Boolean = false,
                     updateMinutes: Int     = 15,
                     crossHair    : Boolean = true,
                     textShift    : Int     = 2, // 3, // 4,
                     textYPad     : Int     = 12,
                     textXPad     : Int     = 36,
                     fontSize     : Double  = 20.0,
                     crossEyed    : Boolean = false,
                     dials        : Boolean = false,
                     minusMonths  : Int     = 1,
                     threshEntries: Boolean = true,
                     desktop      : Boolean = false,
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
      val username: Opt[String] = opt("user", short = 'u', /*required = true,*/ default = Some(default.username),
        descr = "Mastodon bot user name."
      )
      val password: Opt[String] = opt("pass", short = 'p', /*required = true,*/ default = Some(default.password),
        descr = "Mastodon bot password."
      )
      val initDelay: Opt[Int] = opt("init-delay", default = Some(default.initDelay),
        descr = s"Initial delay in seconds (to make sure date-time is synced) (default: ${default.initDelay})."
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
      val imgCrop: Opt[Int] = opt("crop", default = Some(default.imgCrop),
        descr = s"Crop on each side of the image in pixels (default: ${default.imgCrop}).",
        validate = x => x >= 0
      )
      val yShift: Opt[Int] = opt("y-shift", default = Some(default.yShift),
        descr = s"Vertical shift in pixels (default: ${default.yShift}).",
      )
      val textShift: Opt[Int] = opt("text-shift", default = Some(default.textShift),
        descr = s"Horizontal text 'hover' shift in pixels (default: ${default.textShift}).",
      )
      val textXPad: Opt[Int] = opt("text-x-pad", default = Some(default.textXPad),
        descr = s"Horizontal text border padding in pixels (default: ${default.textXPad}).",
      )
      val textYPad: Opt[Int] = opt("text-y-pad", default = Some(default.textYPad),
        descr = s"Vertical text border padding in pixels (default: ${default.textYPad}).",
      )
      val fontSize: Opt[Double] = opt("font-size", default = Some(default.fontSize),
        descr = s"Font size in pixels (default: ${default.fontSize}).",
      )
      val noView: Opt[Boolean] = opt("no-view", default = Some(!default.hasView),
        descr = "Do not open window."
      )
      val skipUpdate: Opt[Boolean] = opt("skip-update", default = Some(default.skipUpdate),
        descr = "Do not check online for updates."
      )
      val updateMinutes: Opt[Int] = opt("update-minutes", default = Some(default.updateMinutes),
        descr = s"Repeated update check in minutes, or zero to disable (default: ${default.updateMinutes})."
      )
      val noCrossHair: Opt[Boolean] = opt("no-cross-hair", default = Some(!default.crossHair),
        descr = "Do not draw cross hair."
      )
      val crossEyed: Opt[Boolean] = opt("cross-eyed", default = Some(default.crossEyed),
        descr = "Render for cross-eyed viewing instead of stereoscopic lenses"
      )
      val shutdownHour: Opt[Int] = opt("shutdown-hour",
        descr = s"Hour of Pi shutdown (or 0 to avoid scheduled shutdown) (default: ${default.shutdownHour})",
        default = Some(default.shutdownHour),
        validate = x => x >= 0 && x <= 24
      )
      val noShutdown: Opt[Boolean] = opt("no-shutdown", default = Some(!default.shutdown),
        descr = "Do not shutdown Pi after completion."
      )
      val dials: Opt[Boolean] = opt("dials", default = Some(default.dials),
        descr = "Installation has physical left/right dials and shutdown button."
      )
      val minusMonths: Opt[Int] = opt("minus-months", default = Some(default.minusMonths),
        descr = s"Number of months between left and right image (default: ${default.minusMonths})."
      )
      val noThreshEntries: Opt[Boolean] = opt("no-thresh-entries", default = Some(!default.threshEntries),
        descr = "Do not apply history threshold to entries."
      )
      val desktop: Opt[Boolean] = opt(name = "desktop", default = Some(default.desktop),
        descr = "Use on desktop where no GPIO is present."
      )

      verify()
      val config: Config = Config(
        username      = username(),
        password      = password(),
        initDelay     = initDelay(),
        verbose       = verbose(),
        baseURI       = baseURI(),
        accountId     = accountId(),
        imgExtent     = imgExtent(),
        imgCrop       = imgCrop(),
        hasView       = !noView(),
        yShift        = yShift(),
        textShift     = textShift(),
        textXPad      = textXPad(),
        textYPad      = textYPad(),
        skipUpdate    = skipUpdate(),
        crossHair     = !noCrossHair(),
        fontSize      = fontSize(),
        crossEyed     = crossEyed(),
        shutdownHour  = shutdownHour(),
        updateMinutes = updateMinutes(),
        shutdown      = !noShutdown(),
        dials         = dials(),
        minusMonths   = minusMonths(),
        threshEntries = !noThreshEntries(),
        desktop       = desktop(),
      )
    }

    run()(p.config)
  }

  private val writeLock = new AnyRef

  lazy val font1pt: Font = {
    val url = getClass.getResource("/LibreFranklin-Regular.ttf")
    try {
      val is  = url.openStream()
      try {
        Font.createFont(Font.TRUETYPE_FONT, is)
      } finally {
        is.close()
      }
    } catch {
      case NonFatal(ex) =>
        Console.err.println("Could not read font:")
        ex.printStackTrace()
        new Font(Font.SANS_SERIF, Font.PLAIN, 1)
    }
  }

  final def name          : String = "Kontakt (window)"
  final def nameAndVersion: String = s"$name $fullVersion"

  def run()(implicit config: Config): Unit = {
    println(nameAndVersion)

    val initDelayMS = math.max(0, config.initDelay) * 1000L
    if (initDelayMS > 0) {
      println(s"Waiting for ${config.initDelay} seconds.")
      Thread.sleep(initDelayMS)
    }

    val odt       = OffsetDateTime.now()
    val date      = Date.from(odt.toInstant)
    println(s"The date and time: $date")

    implicit val as: ActorSystem = ActorSystem()
    var view = Option.empty[View]
    if (config.hasView) Swing.onEDT {
      val _view = openView()
      if (config.dials) {
        val m = Dials.run(Dials.Config(desktop = config.desktop))
        _view.installKeyboardDials(m)
        m.addListener {
          case Dials.Left (inc) => println(s"Left : $inc")
          case Dials.Right(inc) => println(s"Right: $inc")
          case Dials.Off        => quitOrShutdown()
        }
      }
      view = Some(_view)
    }

    lazy val timer = new Timer

    def quitOrShutdown(): Unit = {
      log("About to shut down...")
      if (config.shutdown) Thread.sleep(8000)
      writeLock.synchronized {
        if (config.shutdown) shutdown() else sys.exit()
      }
    }

    val entriesFut = if (config.skipUpdate) readCache() else {
      login().flatMap { implicit li =>
        val e0Fut = updateEntries()
        if (config.skipUpdate || config.updateMinutes == 0) e0Fut else e0Fut.andThen {
          case Success(e0) =>
            val updateMillis = config.updateMinutes * 60 * 1000L
            timer.schedule(new TimerTask {
              override def run(): Unit = {
                log("running repeated update check...")
                updateEntries().foreach { e1 =>
                  val hasNew = e1 != e0
                  log(s"Update checked. New contents? $hasNew")
                  if (hasNew && config.hasView) {
                    fetchPair(e1.last, e1.head).foreach { case (cOld, cNew) =>
                      Swing.onEDT {
                        view.foreach { v =>
                          v.left  = Some(cOld)
                          v.right = Some(cNew)
                        }
                      }
                    }
                  }
                }
              }
            }, updateMillis, updateMillis)
        }
      }
    }

    val futAll = for {
      entries <- entriesFut
      eLeft   = closestIndex(entries, entries.head.createdAt.minusMonths(config.minusMonths))
      eRight  = entries.head
      (cOld, cNew) <- fetchPair(eLeft, eRight)
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
      case Success(_) => log(if (config.skipUpdate) "Entries read" else "Updates completed")
      case Failure(ex) =>
        Console.err.println("Update failed:")
        ex.printStackTrace()
    }

    if (config.shutdownHour > 0) {
      val odtSD0  = odt.withHour(config.shutdownHour % 24).truncatedTo(ChronoUnit.HOURS)
      val odtSD   = if (odtSD0.isAfter(odt)) odtSD0 else {
        println("WARNING: Shutdown hour lies on next day. Shutting down in two hours instead!")
        odt.plus(2, ChronoUnit.HOURS)
      }
      val dateSD  = Date.from(odtSD.toInstant)
      timer.schedule(new TimerTask {
        override def run(): Unit = quitOrShutdown()
      }, dateSD)
      log(s"Shutdown scheduled for $dateSD")
    }
  } // end run

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
        writeLock.synchronized {
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

  def log(what: => String)(implicit config: Config): Unit =
    if (config.verbose) println(s"[${new Date}] kontakt - $what")

  case class Content(
                    textTop   : String,
                    textBottom: String,
                    image     : BufferedImage,
                    )

  def htmlToPlain(str: String): String = {
    val t0        = str.trim
    val tagStart  = "<p>"
    val tagEnd    = "</p>"
    val t   = if (t0.startsWith(tagStart) && t0.endsWith(tagEnd)) {
      t0.substring(tagStart.length, t0.length - tagEnd.length)
    } else {
      t0
    }
    // cf. https://github.com/tootsuite/documentation/issues/884
    // cf. https://stackoverflow.com/questions/21883496/how-to-decode-xhtml-and-or-html5-entities-in-java
//    val u0 = StringEscapeUtils.unescapeXml(t)
//    u0 // StringEscapeUtils.unescapeHtml4(u0)
//    StringEscapeUtils.unescapeHtml4(t.replace("&apos;", "\'"))
    HtmlEscape.unescapeHtml(t)
  }

  // note: also fails if entries is empty
  def fetchPair(eLeft: Entry, eRight: Entry) /*(implicit config: Config)*/: Future[(Content, Content)] =
    Future {
      def readOne(e: Entry): Content = {
        val f = localImageFile(e)
        val image = blocking {
          ImageIO.read(f)
        }
//        val imgSc     = image.getScaledInstance(config.imgExtent, config.imgExtent, Image.SCALE_SMOOTH)
        val sep     = " - "
        val plain   = htmlToPlain(e.content)
//        println(s"HTML '${e.content}' -> plain '$plain'")
        val sepIdx  = plain.indexOf(sep)
        val text    = if (sepIdx < 0) plain else plain.substring(sepIdx + sep.length)
        Content(textTop = text, textBottom = text, image = image)
      }

      val l0    = readOne(eLeft ) // entries.last)
      val r0    = readOne(eRight) // entries.head)
      val left  = l0.copy(textBottom  = r0.textBottom)
      val right = r0.copy(textTop     = l0.textTop   )

      (left, right)
    }

  // all methods must be called on EDT
  trait View {
    var left  : Option[Content]
    var right : Option[Content]

    def installKeyboardDials(m: Dials.Model): Unit
  }

  def openView()(implicit config: Config): View = {
    require (EventQueue.isDispatchThread)
    new ViewImpl
  }

  private final class ViewImpl(implicit config: Config) extends MainFrame with View {
    peer.setUndecorated(true)

//    title = "Kontakt"

    private val textFont = font1pt.deriveFont(config.fontSize.toFloat)

    private def mkSideView(isLeft: Boolean) = {
      val res = new ViewSideImpl(alignRight = isLeft)
      res.font = textFont
      res
    }

    def installKeyboardDials(m: Dials.Model): Unit = {
      val iMap = leftView.peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
      val aMap = leftView.peer.getActionMap
      val nLeftDec  = "dial-left-dec"
      val nLeftInc  = "dial-left-inc"
      val nRightDec = "dial-right-dec"
      val nRightInc = "dial-right-inc"
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), nLeftDec)
      aMap.put(nLeftDec, new AbstractAction(nLeftDec) {
        def actionPerformed(e: ActionEvent): Unit = m ! Dials.Left(-1)
      })
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), nLeftInc)
      aMap.put(nLeftInc, new AbstractAction(nLeftInc) {
        def actionPerformed(e: ActionEvent): Unit = m ! Dials.Left(+1)
      })
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), nRightDec)
      aMap.put(nRightDec, new AbstractAction(nRightDec) {
        def actionPerformed(e: ActionEvent): Unit = m ! Dials.Right(-1)
      })
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), nRightInc)
      aMap.put(nRightInc, new AbstractAction(nRightInc) {
        def actionPerformed(e: ActionEvent): Unit = m ! Dials.Right(+1)
      })
    }

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

  private final class ViewSideImpl(alignRight: Boolean
                                  )(implicit config: Config)
    extends Component {

    import config.{crossEyed, crossHair, imgCrop, imgExtent, panelHeight, panelWidth, textXPad, textYPad}

    opaque        = true
    preferredSize = new Dimension(panelWidth, panelHeight)

    private val hoverShift = if (alignRight ^ crossEyed) config.textShift else -config.textShift

    private var _data = Option.empty[Content]

    def data: Option[Content] = _data
    def data_=(c: Option[Content]): Unit = {
      _data = c
      repaint()
    }

    private val imgX = if (alignRight) panelWidth - imgExtent else 0
    private val pMin = min(panelWidth, panelHeight)
    private val pMax = max(panelWidth, panelHeight)
    private val imgY = (pMin - imgExtent)/2 + (pMax - pMin)

//    listenTo(mouse.clicks)

    private val colrHover = new Color(0x7F000000, true)

    override protected def paintComponent(g: Graphics2D): Unit = {
      super.paintComponent(g)
      g.setColor(Color.black)
      g.fillRect(0, 0, panelWidth, panelHeight)
      _data.foreach { c =>
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION     , RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING         , RenderingHints.VALUE_RENDER_QUALITY       )
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING , RenderingHints.VALUE_TEXT_ANTIALIAS_ON    )
        val img = c.image
//        g.drawImage(img, imgX, imgY, imgExtent, imgExtent, peer)
        g.drawImage(img, /* dx1 */ imgX, /* dy1 */ imgY, /* dx2 */ imgX + imgExtent, /* dy2 */ imgY + imgExtent,
          /* sx1 */ imgCrop, /* sy1 */ imgCrop, /* sx2 */ img.getWidth - imgCrop, /* sy2 */ img.getHeight - imgCrop,
          peer)

        val fm = g.getFontMetrics
//        g.drawString(c.textTop    , imgX + 8f, imgY + 8f + fm.getAscent)
//        g.drawString(c.textBottom , imgX + 8f, imgY + imgExtent - 8f - fm.getHeight + fm.getAscent)

        val frc = g.getFontRenderContext

        def renderText(isBottom: Boolean): Unit = {
          val text      = if (isBottom) c.textBottom else c.textTop
          val as        = new AttributedString(text)
          as.addAttribute(TextAttribute.FONT, g.getFont)  // lbm ignores Graphics2D font!
          val lbm       = new LineBreakMeasurer(as.getIterator, frc)
          val txtX0     = imgX + textXPad + hoverShift
          val maxTxtW0  = (imgExtent - textXPad * 2).toFloat

          def count(w: Float): (Int, Float) = {
            var lineCnt = 0
            var lastLineWidth = 0f
            lbm.setPosition(0) // 'reset'
            while (lbm.getPosition < text.length) {
              val lay = lbm.nextLayout(w)
              lastLineWidth = lay.getAdvance
              lineCnt += 1
            }
            (lineCnt, lastLineWidth)
          }

          def render(lineCnt: Int, maxTxtW: Float): Unit = {
            val txtX = txtX0 + (maxTxtW0 - maxTxtW) * 0.5f
            val txtH = + fm.getHeight * lineCnt
            g.setColor(colrHover)
            val hoverH = txtH + textYPad * 2
            val hoverY = if (isBottom) imgY + imgExtent - hoverH else imgY
            g.fillRect(imgX, hoverY, imgExtent, hoverH)

            g.setColor(Color.white)
            var txtY = (hoverY + textYPad + fm.getAscent).toFloat
            lbm.setPosition(0) // 'reset'
            while (lbm.getPosition < text.length) {
              val lay = lbm.nextLayout(maxTxtW)
              //          txtY += lay.getAscent
              val dx = (maxTxtW - lay.getAdvance) * 0.5f
              lay.draw(g, txtX + dx, txtY)
              txtY += fm.getHeight // lay.getDescent + lay.getLeading
            }
          }

          val (cnt0, w0)  = count(maxTxtW0)
//          println(s"cnt0 $cnt0, last line ratio: ${maxTxtW0 / w0}")
          if (cnt0 <= 1 || (maxTxtW0 / w0) < 1.8) {
            render(cnt0, maxTxtW0)
          } else {
            // try better
            val lastSpace   = maxTxtW0 - w0
            val perLine     = lastSpace / cnt0
            val maxTxtW1    = maxTxtW0 - perLine * 0.85f
            val (cnt1, w1)   = count(maxTxtW1)
//            println(s"cnt0 $cnt0 w0 $w0 maxTxtW0 $maxTxtW0, cnt1 $cnt1 w1 $w1 maxTxtW1 $maxTxtW1")
            if (cnt1 == cnt0) render(cnt1, maxTxtW1) else render(cnt0, maxTxtW0)
          }
        }

        renderText(false)
        renderText(true )

        if (crossHair) {
          val cx = imgX + imgExtent/2 + hoverShift
          val cy = imgY + imgExtent/2

          def drawHair(cw: Int, ch: Int, colr: Color): Unit = {
            g.setColor(colr)
            g.fillRect(cx - cw, cy - ch, cw * 2, ch * 2)
            g.fillRect(cx - ch, cy - cw, ch * 2, cw * 2)
          }

          drawHair(10, 3, colrHover)
          drawHair( 8, 1, Color.white)
        }
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

  def closestIndex(in: Entries, date: ZonedDateTime): Entry =
    in.seq.minBy(e => math.abs(e.createdAt.until(date, ChronoUnit.HOURS)))

  def truncateEntries(in: Entries)(implicit config: Config): Entries =
    if (in.isEmpty || !config.threshEntries) in else {
      val newest    = in.head.createdAt
      val thresh    = newest.minusMonths(config.minusMonths).minusHours(2)
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
