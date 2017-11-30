package com.kreactive.stream.state

import akka.actor.ActorSystem
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestKit
import com.kreactive.stream.StreamTestKit
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by cyrille on 05/12/2016.
  */
class StateFlowSpec extends TestKit(ActorSystem("connectionStateBidiSpec")) with WordSpecLike with MustMatchers with StreamTestKit {

  private def setState(i: String) = i.headOption.filter(_ => i.length == 3)

  private def flowTest(setOnlyOnce: Boolean = false) = testFlow(StateFlow(setState, setOnlyOnce).map(_.swap))

 "a stateflow" should {
   "set state on first message" in {
     val (pub, sub) = flowTest()

     pub.sendNext("ABC").sendComplete()
     sub.requestNext("ABC", 'A').expectComplete()
   }

   "use state on every message" in {
     val (pub, sub) = flowTest(true)

     pub.sendNext("ABC").
       sendNext("hello").
       sendNext("world").
       sendComplete()
     sub.requestNext("ABC", 'A').
       requestNext("hello", 'A').
       requestNext(("world", 'A')).
       expectComplete()
   }

   "reset state on every relevant message" in {
     val (pub, sub) = flowTest()

     pub.sendNext("ABC").
       sendNext("DEF").
       sendNext("world").
       sendComplete()
     sub.requestNext("ABC", 'A').
       requestNext("DEF", 'D').
       requestNext("world", 'D').
       expectComplete()
   }

   "not reset state on every relevant message if setOnlyOnce is true" in {
     val (pub, sub) = flowTest(setOnlyOnce = true)

     pub.sendNext("ABC").
       sendNext("DEF").
       sendNext("world").
       sendComplete()
     sub.requestNext("ABC", 'A').
       requestNext("DEF", 'A').
       requestNext("world", 'A').
       expectComplete()
   }

   "not send messages while state is not set" in {
     val (pub, sub) = flowTest()

     pub.sendNext("world").
       sendComplete()
     sub.request(1).
       expectComplete()
   }

   "send messages once state is set" in {
     val (pub, sub) = flowTest()

     pub.sendNext("world").
       sendNext("ABC").
       sendComplete()
     sub.requestNext("ABC", 'A').
       expectComplete()
   }
 }
}
