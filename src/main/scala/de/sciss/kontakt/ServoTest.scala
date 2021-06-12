/*
 *  ServoTest.scala
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

import com.pi4j.component.servo.impl.{GenericServo, PCA9685GpioServoProvider}
import com.pi4j.gpio.extension.pca.{PCA9685GpioProvider, PCA9685Pin}
import com.pi4j.io.gpio.GpioFactory
import com.pi4j.io.i2c.{I2CBus, I2CFactory}
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

/*

  to check:

--1
--2
Exception in thread "Thread-0" java.lang.NullPointerException
	at com.pi4j.gpio.extension.pca.PCA9685GpioProvider.validatePin(PCA9685GpioProvider.java:327)
	at com.pi4j.gpio.extension.pca.PCA9685GpioProvider.setAlwaysOff(PCA9685GpioProvider.java:250)
	at com.pi4j.gpio.extension.pca.PCA9685GpioProvider.reset(PCA9685GpioProvider.java:355)
	at com.pi4j.gpio.extension.pca.PCA9685GpioProvider.shutdown(PCA9685GpioProvider.java:384)
	at com.pi4j.io.gpio.impl.GpioControllerImpl.shutdown(GpioControllerImpl.java:1056)
	at com.pi4j.io.gpio.impl.GpioControllerImpl$ShutdownHook.run(GpioControllerImpl.java:991)


 */
object ServoTest {
  case class Config(
                     channel   : Int = 0,
                     angle     : Option[Double] = None,
                     pwm       : Option[Int]    = None,
                   )

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "ServoTest"
      private val default = Config()

      val channel: Opt[Int] = opt(required = true, default = Some(default.channel),
        descr = s"Servo controller channel 0 to 15 (default: ${default.channel}).",
        validate = x => x >= 0 && x < 16
      )
      val angle: Opt[Double] = opt(required = false,
        descr = "Target servo angle -100% to +100%."
      )
      val pwm: Opt[Int] = opt(required = false,
        descr = "PWM value."
      )

      verify()
      val config: Config = Config(
        channel   = channel(),
        angle     = angle.toOption,
        pwm       = pwm  .toOption,
      )
    }

    run(p.config)
  }

  def run(config: Config): Unit = {
    println("ServoTest")
    val gpioProvider  = createProvider()
    println(s"period duration in microseconds: ${gpioProvider.getPeriodDurationMicros}")
    val gpio          = GpioFactory.getInstance
    val pin = config.channel match {
      case  0 => PCA9685Pin.PWM_00
      case  1 => PCA9685Pin.PWM_01
      case  2 => PCA9685Pin.PWM_02
      case  3 => PCA9685Pin.PWM_03
      case  4 => PCA9685Pin.PWM_04
      case  5 => PCA9685Pin.PWM_05
      case  6 => PCA9685Pin.PWM_06
      case  7 => PCA9685Pin.PWM_07
      case  8 => PCA9685Pin.PWM_08
      case  9 => PCA9685Pin.PWM_09
      case 10 => PCA9685Pin.PWM_10
      case 11 => PCA9685Pin.PWM_11
      case 12 => PCA9685Pin.PWM_12
      case 13 => PCA9685Pin.PWM_13
      case 14 => PCA9685Pin.PWM_14
      case 15 => PCA9685Pin.PWM_15
    }
    val servoName = s"Servo_${config.channel}"
    /* val pin = */ gpio.provisionPwmOutputPin(gpioProvider, pin, servoName)
    val servoProvider = new PCA9685GpioServoProvider(gpioProvider)
    val servoDriver   = servoProvider.getServoDriver(pin)
    val servo         = new GenericServo(servoDriver, servoName)

    config.angle match {
      case Some(posD) =>
        val pos           = posD.toFloat
        val pwmDuration   = servo.calculatePwmDuration(pos)
        println(s"position $pos corresponds to pwm duration $pwmDuration")
        servo.setPosition(pos)

      case None =>
    }

    config.pwm match {
      case Some(v) =>
        println(s"setServoPulseWidth($v)")
        servoDriver.setServoPulseWidth(v)

      case None =>
    }

    println("Wait")
    Thread.sleep(4000)
    println("Ok")
  }

  def createProvider(): PCA9685GpioProvider = {
    val bus = I2CFactory.getInstance(I2CBus.BUS_1)
    new PCA9685GpioProvider(bus, 0x40, new java.math.BigDecimal(50.0))
  }
}
