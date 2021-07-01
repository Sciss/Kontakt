/*
 *  RandomizeAnnot.scala
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

import de.sciss.file._
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.io.FileOutputStream
import scala.util.Random

object RandomizeAnnot {
  case class Config(from: Int = 1)

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "RandomizeAnnot"
      val from: Opt[Int] = opt(required = true,
        descr = "Begin at line",
        validate = x => x >= 1
      )

      verify()
      val config: Config = Config(
        from = from(),
      )
    }

    run(p.config)
  }

  def run(c: Config): Unit = {
    val f  = file("src") / "main" / "resources" / "annotations.txt"
    val charset = "UTF-8"
    val in = {
      val src = io.Source.fromFile(f, charset)
      try {
        src.getLines().toList
      } finally {
        src.close()
      }
    }
    val (init, tail) = in.splitAt(c.from - 1)
    val r = new Random()
    val tailR = r.shuffle(tail)
    val out = init ++ tailR
    if (in == out) {
      println("Order did not change!")
      return
    }
    val fOut = new FileOutputStream(f)
    try {
      fOut.write(out.mkString("\n").getBytes(charset))
    } finally {
      fOut.close()
    }
  }
}
