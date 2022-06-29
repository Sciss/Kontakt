/*
 *  Dials.scala
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

import com.pi4j.io.gpio.event.{GpioPinDigitalStateChangeEvent, GpioPinListenerDigital}
import com.pi4j.io.gpio.{GpioFactory, Pin, PinPullResistance, RaspiPin}
import de.sciss.model
import de.sciss.model.impl.ModelImpl
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

import java.util.{Timer, TimerTask}

/** Controllers for the image dials and shutdown-button in the physical installation. */
object Dials {
  case class Config(
                     gpioLeftA    : Int = 5, // 1,
                     gpioLeftB    : Int = 6, // 4,
                     gpioRightA   : Int = 1, // 5,
                     gpioRightB   : Int = 4, // 6,
                     gpioPowerOff : Int = 7,
                     offDuration  : Float = 3f,
                     desktop      : Boolean = false
                   )

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "ServoTest"
      private val default = Config()

      val gpioLeftA: Opt[Int] = opt(name = "gpio-left-a", default = Some(default.gpioLeftA),
        descr = s"GPIO pin id for left dial, first output (default: ${default.gpioLeftA}).",
        validate = x => x >= 0 && x <= 7
      )
      val gpioLeftB: Opt[Int] = opt(name = "gpio-left-b", default = Some(default.gpioLeftB),
        descr = s"GPIO pin id for left dial, second output (default: ${default.gpioLeftB}).",
        validate = x => x >= 0 && x <= 7
      )
      val gpioRightA: Opt[Int] = opt(name = "gpio-right-a", default = Some(default.gpioRightA),
        descr = s"GPIO pin id for right dial, first output (default: ${default.gpioRightA}).",
        validate = x => x >= 0 && x <= 7
      )
      val gpioRightB: Opt[Int] = opt(name = "gpio-right-b", default = Some(default.gpioRightB),
        descr = s"GPIO pin id for right dial, second output (default: ${default.gpioRightB}).",
        validate = x => x >= 0 && x <= 7
      )
      val offDuration: Opt[Float] = opt(name = "off-duration", default = Some(default.offDuration),
        descr = s"Duration in seconds, for keeping pressed to issue shutdown. (default: ${default.offDuration})"
      )
      val desktop: Opt[Boolean] = opt(name = "desktop", default = Some(default.desktop),
        descr = "Use on desktop where no GPIO is present."
      )
      verify()
      val config: Config = Config(
        gpioLeftA   = gpioLeftA(),
        gpioLeftB   = gpioLeftB(),
        gpioRightA  = gpioRightA(),
        gpioRightB  = gpioRightB(),
        offDuration = offDuration(),
        desktop     = desktop(),
      )
    }

    val c = p.config
    println("Dial")
    val timer = new Timer
    val m = run(c, timer)
    m.addListener {
      case Left(inc) =>
        println(s"Left : $inc")
      case Right(inc) =>
        println(s"Right : $inc")
      case Off =>
        println("Button off.")
        sys.exit()
    }
  }

  private def parsePin(i: Int): Pin = i match {
    case  0 => RaspiPin.GPIO_00
    case  1 => RaspiPin.GPIO_01
    case  2 => RaspiPin.GPIO_02
    case  3 => RaspiPin.GPIO_03
    case  4 => RaspiPin.GPIO_04
    case  5 => RaspiPin.GPIO_05
    case  6 => RaspiPin.GPIO_06
    case  7 => RaspiPin.GPIO_07
    case  8 => RaspiPin.GPIO_08
    case  9 => RaspiPin.GPIO_09
    case 10 => RaspiPin.GPIO_10
    case 11 => RaspiPin.GPIO_11
    case 12 => RaspiPin.GPIO_12
    case 13 => RaspiPin.GPIO_13
    case 14 => RaspiPin.GPIO_14
    case 15 => RaspiPin.GPIO_15
    case 16 => RaspiPin.GPIO_16
    case 17 => RaspiPin.GPIO_17
    case 18 => RaspiPin.GPIO_18
    case 19 => RaspiPin.GPIO_19
    case 20 => RaspiPin.GPIO_20
    case 21 => RaspiPin.GPIO_21
    case 22 => RaspiPin.GPIO_22
    case 25 => RaspiPin.GPIO_25
    case 27 => RaspiPin.GPIO_27
    case 28 => RaspiPin.GPIO_28
    case 29 => RaspiPin.GPIO_29
    case _ =>
      sys.error(s"Illegal pin $i")
  }

  sealed trait Update
  case class Left (dir: Int) extends Update
  case class Right(dir: Int) extends Update
  case object Off extends Update

  trait Model extends model.Model[Update] {
    def ! (update: Update): Unit
  }

  def run(config: Config, timer: Timer): Model = {
    object model extends ModelImpl[Update] with Model {
      override def !(update: Update): Unit = dispatch(update)
    }

    if (config.desktop) return model

    val gpio      = GpioFactory.getInstance
    val pinLeftA  = parsePin(config.gpioLeftA)
    val pinLeftB  = parsePin(config.gpioLeftB)
    val pinRightA = parsePin(config.gpioRightA)
    val pinRightB = parsePin(config.gpioRightB)

    def mkDial(pinA: Pin, pinB: Pin)(fun: Int => Unit): Unit = {
      val inA       = gpio.provisionDigitalInputPin(pinA, PinPullResistance.PULL_UP)
      val inB       = gpio.provisionDigitalInputPin(pinB, PinPullResistance.PULL_UP)
      // if (config.debounce > 0) button.setDebounce(config.debounce)
      var stateA    = true
      var stateB    = true
      var lastDir   = 0
      var lastTime  = System.currentTimeMillis()
      var sameCount = 0
      var speed     = 1

      def check(dir: Int): Unit =
        if (stateA == stateB) {
          val now = System.currentTimeMillis()

          def resetSpeed(): Unit = {
            sameCount = 0
            speed     = 1
          }

          if (lastDir != dir) {
            lastDir = dir
            resetSpeed()
          }
          // the time-out is higher if we've been already actively scrolling,
          // so one can move the hand back on the dial after a half rotation
          val dt = if (sameCount >= 6) 800 else 400
          if ((now - lastTime) < dt) {
            sameCount += 1
            if (sameCount >= 24) {
              if (speed < 32) speed <<= 1
              sameCount = 6
            }
          } else {
            resetSpeed()
          }
          lastTime  = now
          val inc   = dir * speed
          fun(inc)
        }

      inA.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(e: GpioPinDigitalStateChangeEvent): Unit = {
          stateA = e.getState.isHigh
          check(-1)
        }
      })
      inB.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(e: GpioPinDigitalStateChangeEvent): Unit = {
          stateB = e.getState.isHigh
          check(+1)
        }
      })
    }

    val pinPowerOff = parsePin(config.gpioPowerOff)
    val inPowerOff  = gpio.provisionDigitalInputPin(pinPowerOff, PinPullResistance.PULL_UP)
    inPowerOff.setDebounce(20)

    mkDial(pinLeftA , pinLeftB  )(inc => model.!(Left (inc)))
    mkDial(pinRightA, pinRightB )(inc => model.!(Right(inc)))

    inPowerOff.addListener(new GpioPinListenerDigital() {
//      private var timePressed = Long.MaxValue
      private val durMillis   = (config.offDuration * 1000).toLong

      private var scheduled   = Option.empty[TimerTask]

      override def handleGpioPinDigitalStateChangeEvent(e: GpioPinDigitalStateChangeEvent): Unit = {
        println(s"button: ${e.getState}")
        val pressed = !e.getState.isHigh
//        val t       = System.currentTimeMillis()
        scheduled.foreach(_.cancel())
        scheduled = None
        if (pressed) {
          val tt = new TimerTask {
            override def run(): Unit = model.!(Off)
          }
          timer.schedule(tt, durMillis)
//          timePressed = t
          scheduled = Some(tt)
//        } else {
//          if ((t - durMillis) > timePressed) {
//            model.!(Off)
//          }
        }
      }
    })

    model
  }
}
