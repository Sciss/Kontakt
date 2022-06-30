/*
 *  Scheduler.scala
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

import java.util.Date

trait Scheduler {
  type Token

  def scheduleSec(sec: Double)(body: => Unit): Token =
    schedule((sec * 1000L + 0.5).toLong)(body)

  def schedule(dt: Long)(body: => Unit): Token

  def schedule(dt: Long, period: Long)(body: => Unit): Token

  def schedule(date: Date)(body: => Unit): Token

  def cancel(token: Token): Unit

//  def onEDT(body: => Unit): Unit
}
