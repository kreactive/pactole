package com.kreactive.akka

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.{MustMatchers, TestSuite, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by cyrille on 18/01/2017.
  */
class MultiImplementedExtensionSpec extends TestSuite with WordSpecLike with MustMatchers {


  "a MultiImplemented extension" should {
    "use the extension started by the conf if it exists" in {
      val system = ActorSystem("MITestByConf", ConfigFactory.parseString("""akka.extensions: ["com.kreactive.akka.MyExt2"]"""))
      MyExt(system).hello mustBe "MyExt2"
      Await.result(system.terminate(), 3.seconds)
    }
    "use the default extension if none is started with the system" in {
      val system = ActorSystem("MITestWithoutConf")
      MyExt(system).hello mustBe "MyExt1"
      Await.result(system.terminate(), 3.seconds)
    }
    "throw an error if used with ExtensionIdProvider" in {
      assertThrows[NoSuchElementException]{
        val system = ActorSystem("MITestBadByConf", ConfigFactory.parseString("""akka.extensions: ["com.kreactive.akka.BadMyExtWithProvider"]"""))
        BadMyExtWithProvider(system).hello
        Await.result(system.terminate(), 3.seconds)
      }
    }
    "throw an error if used with no possibilities" in {
      val system = ActorSystem("MITestBadEmpty")
      assertThrows[NoSuchElementException]{
        BadMyExtEmpty(system).hello
      }
      Await.result(system.terminate(), 3.seconds)
    }
  }

}




