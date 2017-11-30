package com.kreactive.stream

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.duration._
import scala.reflect.ClassTag
/**
  * Created by cyrille on 19/12/2016.
  */
class FlowsSpec extends TestKit(ActorSystem("flows")) with StreamTestKit with MustMatchers with WordSpecLike {

  private class FakeActor extends Actor {

    override def receive = {
      case () =>
        context.become(empty)

      case msg =>
        sender() ! List("hello world!")
    }

    def empty: Receive = {
      case _ => ()
    }
  }
  implicit val timeout = Timeout(3.seconds)
  import Flows._

  "an actor flow" should {
    "reply to its requests" in {
      val (pub, sub) = testFlow(actor[String, List[String]](system.actorOf(Props(new FakeActor)), ()))

      pub.sendNext("hello").sendNext("world").sendComplete()
      sub.
        requestNext(List("hello world!")).
        requestNext(List("hello world!")).
        request(1).
        expectComplete()
    }
  }
  
}
