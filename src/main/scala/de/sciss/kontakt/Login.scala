/*
 *  Login.scala
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
import de.sciss.scaladon.Mastodon.Scope
import de.sciss.scaladon.{AccessToken, Id, Mastodon}
import net.harawata.appdirs.AppDirsFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Login {
  def appName   : String = "kontakt"
  def appAuthor : String = "de.sciss"

  trait Config {
    def username: String
    def password: String
    def baseURI : String
  }

  def apply(write: Boolean, wipe: Boolean = false)(implicit config: Config, as: ActorSystem): Future[Login] = {
    import config._
    import de.sciss.scaladon.Mastodon

    val appDirs     = AppDirsFactory.getInstance
    val configBase  = appDirs.getUserConfigDir(appName, /* version */ null, /* author */ appAuthor)
    val appScopes   = Set[Scope](Scope.Read, Scope.Write)
    val scopes      = if (write) appScopes else Set[Scope](Scope.Read)
    val clientName  = "kontakt_tooter"
    if (wipe) {
      val res = Mastodon.deleteAppData(baseURI = baseURI, clientName = clientName, storageLoc = configBase)
      if (!res) println("Failed to delete Mastodon app data.")
    }

    for {
      app <- Mastodon.createApp(baseURI = baseURI, clientName = clientName,
        scopes = appScopes, storageLoc = configBase)
      token <- app.login(username = username, password = password, scopes = scopes)
    } yield
      new Login(app, token, as)
  }
}
class Login(val app: Mastodon,
            implicit val token: AccessToken,
            implicit val actorSystem: ActorSystem
           )
