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
}
