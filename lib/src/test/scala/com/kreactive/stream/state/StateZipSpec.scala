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
class StateZipSpec extends TestKit(ActorSystem("connectionStateBidiSpec")) with WordSpecLike with MustMatchers with StreamTestKit {

  private def testZipFlow[T](sourceProbe: Source[String, T], setOnlyOnce: Boolean = false) = Flow.fromGraph(
    GraphDSL.create(sourceProbe) { implicit b =>
      stateSource =>
        import GraphDSL.Implicits._

        val zip = b.add(StateZip[String, String](setOnlyOnce))

        stateSource.out ~> zip.in0

        FlowShape(zip.in1, zip.out)
    })

  private def testZip(setOnlyOnce: Boolean = false) =
    testFlowMat(testZipFlow(TestSource.probe[String], setOnlyOnce))(Keep.both)

  "a stateZip" should {
    "set state on first message" in {
      val ((pub, sub), pubState) = testZip()

      pubState.sendNext("state1").sendComplete()
      pub.sendNext("elem").sendComplete()
      sub.requestNext("state1", "elem").expectComplete()
    }

    "forward state with every message" in {
      val ((pub, sub), pubState) = testZip()

      pubState.
        sendNext("state1").
        sendComplete()
      pub.
        sendNext("elem").
        sendNext("elem2").
        sendNext("elem3").
        sendComplete()
      sub.
        requestNext("state1", "elem").
        requestNext("state1", "elem2").
        requestNext("state1", "elem3").
        expectComplete()
    }

    "reset state on every state message" in {
      val ((pub, sub), pubState) = testZip()

      pubState.sendNext("state1")
      pub.
        sendNext("elem").
        sendNext("elem2")
      sub.
        requestNext("state1", "elem").
        requestNext("state1", "elem2")
      pubState.
        sendNext("state2").
        sendComplete()
      pub.
        sendNext("elem3").
        sendComplete()
      sub.
        requestNext("state2", "elem3").
        expectComplete()
    }

    "not reset state on every state message if setOnlyOnce" in {
      val ((pub, sub), pubState) = testZip(setOnlyOnce = true)

      pubState.sendNext("state1")
      pub.
        sendNext("elem").
        sendNext("elem2")
      pubState.
        sendNext("state2").
        sendComplete()
      pub.
        sendNext("elem3").
        sendComplete()
      sub.
        requestNext("state1", "elem").
        requestNext("state1", "elem2").
        requestNext("state1", "elem3").
        expectComplete()
    }

    "not send messages while state is not set" in {
      val ((pub, sub), pubState) = testZip()

      pub.
        sendNext("world").
        sendComplete()
      pubState.sendComplete()
      sub.
        request(1).
        expectComplete()
    }

    "send messages only once state is set" in {
      val ((pub, sub), pubState) = testZip()

      pub.
        sendNext("hello")
      pubState.
        sendNext("state").
        sendComplete()
      pub.
        sendNext("world").
        sendComplete()
      sub.
        requestNext("state", "world").
        expectComplete()
    }

    "keep its last state once state flow is complete" in {
      val ((pub, sub), pubState) = testZip()

      pubState.
        sendNext("state").
        sendComplete()
      pub.
        sendNext("hello").
        sendComplete()
      sub.
        requestNext("state", "hello").
        expectComplete()
    }

    "pull on the state stream whatever happens" in {
      val (pubState, pulledF) = Source.empty.viaMat(testZipFlow(TestSource.probe[String].alsoToMat(Sink.seq)(Keep.both)))(Keep.right).to(Sink.ignore).run()

      pubState.sendNext("state1")
      pubState.sendNext("state2")
      pubState.sendNext("state3")
      pubState.sendComplete()

      Await.result(pulledF, 3.seconds) mustBe List(1, 2, 3).map("state" + _)

    }
  }
}
