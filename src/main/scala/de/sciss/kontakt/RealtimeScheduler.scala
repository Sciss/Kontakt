/*
 *  RealtimeScheduler.scala
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

import java.util.{Date, TimerTask}
import java.{util => ju}
import scala.swing.Swing

final class RealtimeScheduler extends Scheduler {
  private[this] val peer = new ju.Timer

  override type Token = TimerTask

//  override def onEDT(body: => Unit): Unit = Swing.onEDT(body)

  private def wrap(body: => Unit): Token =
    new TimerTask {
      override def run(): Unit = body
    }

  override def schedule(dt: Long)(body: => Unit): Token = {
    val tt = wrap(body)
    peer.schedule(tt, dt)
    tt
  }

  override def schedule(dt: Long, period: Long)(body: => Unit): Token = {
    val tt = wrap(body)
    peer.schedule(tt, dt, period)
    tt
  }

  override def schedule(date: Date)(body: => Unit): Token = {
    val tt = wrap(body)
    peer.schedule(tt, date)
    tt
  }

  override def cancel(token: Token): Unit = token.cancel()
}