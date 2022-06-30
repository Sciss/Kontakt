/*
 *  OfflineScheduler.scala
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
import scala.collection.mutable

class OfflineScheduler(fps: Int, sync: AnyRef) extends Scheduler {
  type Token = Int

  private final class Timed(val frame: Int, val fun: () => Unit)

//  private[this] val sync        = new AnyRef
  private[this] var tokenCount  = 0
  private[this] val fpMs        = fps / 1000.0
  private[this] var _frame      = 1
  private[this] val queue       = mutable.SortedMap.empty[Int, Vector[Int]]
  private[this] val tokenMap    = mutable.Map.empty[Int, Timed]

//  override def onEDT(body: => Unit): Unit = body

  def frame: Int = sync.synchronized(_frame)

  def advance(dt: Long)(render: => Unit): Int = {
    val df = (dt * fpMs + 0.5).toInt
    advanceFrames(maxFrames = df)(render)
  }

  def isEmpty: Boolean = sync.synchronized {
    queue.isEmpty
  }

  def nonEmpty: Boolean = !isEmpty

    /** @return   the number of frames actually advanced */
  def advanceFrames(maxFrames: Int)(render: => Unit): Int = sync.synchronized {
    if (queue.isEmpty) 0 else {
      val target = queue.firstKey
      // println(s"--- target $target")
      var df = 0
      while (_frame < target && df < maxFrames) {
        _frame += 1
        df += 1
        // println(s"--- frame ${_frame}")
        render
      }
      if (_frame == target) {
        // println(s"--- reached $target")
        queue.remove(target).foreach { tokens =>
          tokens.foreach { token =>
            tokenMap.remove(token).foreach { timed =>
              timed.fun.apply()
            }
          }
        }
      }
      df
    }
  }

  override def schedule(dt: Long)(body: => Unit): Token = sync.synchronized {
    require (dt >= 0L)
    val token = tokenCount
    tokenCount += 1
    val df = (dt * fpMs + 0.5).toInt
    val target = frame + df
    val fun: () => Unit = () => body
    queue.updateWith(target) { vOldOpt =>
      val vOld = vOldOpt.getOrElse(Vector.empty)
      val vNew = vOld :+ token
      Some(vNew)
    }
    val timed = new Timed(target, fun)
    tokenMap.put(token, timed)
    token
  }

  override def cancel(token: Token): Unit = sync.synchronized {
    tokenMap.remove(token).foreach { timed =>
      queue.updateWith(timed.frame) { vOldOpt =>
        val vOld = vOldOpt.getOrElse(Vector.empty)
        val vNew = vOld.filterNot(_ == token)
        if (vNew.isEmpty) None else Some(vNew)
      }
    }
  }

  override def schedule(dt: Long, period: Long)(body: => Unit): Token =
    throw new UnsupportedOperationException()

  override def schedule(date: Date)(body: => Unit): Token =
    throw new UnsupportedOperationException()
}
