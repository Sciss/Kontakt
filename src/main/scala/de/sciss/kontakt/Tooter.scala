/*
 *  Tooter.scala
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
import de.sciss.scaladon.{Id, Mastodon, StatusVisibility}
import de.sciss.scaladon.Mastodon.Scope
import net.harawata.appdirs.AppDirsFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object Tooter {
  def main(args: Array[String]): Unit = {
    require (args.length >= 2, "Must provide username (or e-mail) and password")
    val tootOpt = if (args.length >= 3) Some(args(2)) else None

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir("kontakt", /* version */ null, /* author */ "de.sciss")
//    println(configBase)

//    println(Scope.all.mkString(" "))
//    sys.exit()

    implicit val as: ActorSystem = ActorSystem()

    val testFut = for {
      app   <- Mastodon.createApp(baseURI = "botsin.space", clientName = "kontakt_tooter",
        scopes = Set(Scope.Read, Scope.Write), storageLoc = configBase)
      token <- app.login(username = args(0), password = args(1))
      res   <- tootOpt match {
        case Some(text) => app.toot(status = text, StatusVisibility.Public)(token)
        case None       => app.Accounts.fetch(Id("304274"))(token)
      }
    } yield {
      res
    }

    Await.ready(testFut, Duration.Inf)
    testFut.value.get match {
      case Success(data) =>
        println("Successful.")
        println(data)
        sys.exit()

      case Failure(ex) =>
        println("Failed:")
        ex.printStackTrace()
        sys.exit(1)
    }
  }
}
