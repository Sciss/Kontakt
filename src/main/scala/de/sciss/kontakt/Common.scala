/*
 *  Common.scala
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

import de.sciss.scaladon.Id
import org.unbescape.html.HtmlEscape

import scala.util.control.NonFatal

object Common {
  private def buildInfString(key: String): String = try {
    val clazz = Class.forName("de.sciss.kontakt.BuildInfo")
    val m     = clazz.getMethod(key)
    m.invoke(null).toString
  } catch {
    case NonFatal(_) => "?"
  }

  final def version       : String = buildInfString("version")
  final def builtAt       : String = buildInfString("builtAtString")
  final def fullVersion   : String = s"v$version, built $builtAt"

  def shutdown(): Unit = {
    import sys.process._
    Seq("sudo", "shutdown", "now").!
  }

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

  def longId(x: Id): Long =
    try {
      x.value.toLong
    } catch {
      case _: Exception => -1L
    }

  def idLong(x: Long): Id = Id(x.toString)
}
