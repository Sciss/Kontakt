/*
 *  TransferTest.scala
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

import java.text.SimpleDateFormat
import java.util.{Calendar, Locale}

// requires g'mic -- `sudo apt install gmic`
object TransferTest {
  def main(args: Array[String]): Unit =
    run()

  def run(): Unit = {
    val baseDir     = file("/data/projects/Kontakt/")
    val inputDir    = baseDir / "materials" / "photos"
    val outputDir   = baseDir / "image_transfer"
    val startDateS  = "210420"
    val endDateS    = "210813"  // exclusive ; XXX TODO: copy newest photos
    val imgRefF     = inputDir / "snap-210615_180209-crop.jpg"
    val dateFmt     = new SimpleDateFormat("yyMMdd"   , Locale.US)
    val imgInFmt    = new SimpleDateFormat("yyMMdd_hh", Locale.US)
    val startDate   = dateFmt.parse(startDateS)
    val endDate     = dateFmt.parse(endDateS)
    val cal         = Calendar.getInstance()
    val calEnd      = Calendar.getInstance()
    cal   .setTime(startDate)
    calEnd.setTime(endDate  )
    val imgFileInSet = inputDir.children { _.name.contains("-crop") }

    var frameIdx    = 1
    while (cal.getTime.before(endDate)) {
      // println(cal.getTime)
      for (hour <- Seq(0, 6, 12, 18)) {
        cal.set(Calendar.HOUR_OF_DAY, hour)
        val namePart = imgInFmt.format(cal.getTime)
        val imgInFOpt = imgFileInSet.find(_.name.contains(namePart))
        // println(s"$namePart -> $imgInFOpt")
        imgInFOpt.foreach { imgInF =>
          val imgOutF = outputDir / "transfer-%d.jpg".formatLocal(Locale.US, frameIdx)
          println(imgOutF.name)
          if (!imgOutF.exists()) {
            val cmd = Seq("gmic",
              "-input", imgRefF.path, "-input", imgInF.path, "-transfer_histogram[1]", "[0]", "-output[1]", imgOutF.path
            )
            import sys.process._
            val code = cmd.!
            require (code == 0, s"${cmd.mkString(" ")} failed with code $code")
          }
          frameIdx += 1
        }
      }
      cal.add(Calendar.DAY_OF_MONTH, 1)
    }

    // snap-210422_060305-crop.jpg
  }
}
