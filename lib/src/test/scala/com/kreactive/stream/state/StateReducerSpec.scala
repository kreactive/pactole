package com.kreactive.stream.state

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import com.kreactive.stream.StreamTestKit
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.Future

/**
  * Created by cyrille on 13/12/2016.
  */
class StateReducerSpec extends TestKit(ActorSystem("StateReducer")) with WordSpecLike with MustMatchers with StreamTestKit {

  private def testReducer = testFlow(StateReducer.flow[String, String]{
    case (aa, b) if aa.forall(_.takeRight(4) != "STOP") => Future.successful(Some(aa.map(_ + " ").getOrElse("") + b))
  })

  "a scanAsync" should {
    "mutatrequeste the state" in {
      val (pub, sub) = testFlow(Flow[String].scanAsync[Option[String]](None)((s, i) => Future.successful(Some(s.map(_ + " ").getOrElse("") + i))).drop(1))

      pub.
        sendNext("hello").
        sendNext("world").
        sendComplete()
      sub.
        requestNext(Some("hello")).
        requestNext(Some("hello world")).
        expectComplete()
    }
  }

  "a StateReducer" should {
    "mutate the state on every relevant case" in {
      val (pub, sub) = testReducer
      pub.
        sendNext("hello").
        sendNext("world").
        sendComplete()
      sub.
        requestNext("hello", "hello").
        requestNext("hello world", "world").
        expectComplete()
    }

    "not mutate the state on non relevant cases" in {
      val (pub, sub) = testReducer

      pub.
        sendNext("STOP").
        sendNext("hello").
        sendNext("world").
        sendComplete()
      sub.
        requestNext("STOP", "STOP").
        requestNext("STOP", "hello").
        requestNext("STOP", "world").
        expectComplete()
    }
  }

}
