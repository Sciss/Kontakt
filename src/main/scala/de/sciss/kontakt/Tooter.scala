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

import java.io.File
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object Tooter {
  sealed trait Action
  case class GetAccount(id: Id) extends Action
  case class SendToot(text: String) extends Action
  case class UploadMedia(f: File) extends Action

  def main(args: Array[String]): Unit = {
    require (args.length >= 2, "Must provide username (or e-mail) and password")
    val action = if (args.length < 3) GetAccount(Id("304274")) else {
      val s = args(2)
      val f = new File(s)
      if (f.isFile) UploadMedia(f) else SendToot(s)
    }

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
      res   <- action match {
        case SendToot(text) => app.toot(status = text, StatusVisibility.Public)(token)
        case GetAccount(id) => app.Accounts.fetch(id)(token)
        case UploadMedia(f) => app.Statuses.uploadMedia(f)(token)
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
