package de.sciss.kontakt

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ClientConnectionSettings

object AkkaConfigTest {
  def main(args: Array[String]): Unit = {
    // -Dakka.http.client.idle-timeout=61s -Dakka.http.client.connecting-timeout=61s
    sys.props("akka.http.client.idle-timeout") = "66s"
    sys.props("akka.http.client.connecting-timeout") = "66s"

    implicit val as: ActorSystem = ActorSystem()
    val set = ClientConnectionSettings(as)
    println(s"connectingTimeout = ${set.connectingTimeout}")
    println(s"idleTimeout = ${set.idleTimeout}")
  }
}
