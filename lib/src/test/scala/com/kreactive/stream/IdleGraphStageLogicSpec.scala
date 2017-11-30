package com.kreactive.stream

import akka.Done
import akka.scheduler.VTTestKit
import akka.stream._
import akka.stream.scaladsl.Keep
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue}
import com.miguno.akka.testing.VirtualTime
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

/**
  * Created by cyrille on 31/01/2017.
  */
class IdleGraphStageLogicSpec extends VTTestKit("IdleGraphStageLogicSpec", new VirtualTime) with StreamTestKit with WordSpecLike with MustMatchers {

  def flow(init: Option[FiniteDuration], factor: Double, activity: Boolean => Boolean, collect: Option[PartialFunction[Boolean, Option[FiniteDuration]]]) = new GraphStageWithMaterializedValue[FlowShape[Boolean, Boolean], Future[Done]] {
    val in = Inlet[Boolean]("")
    val out = Outlet[Boolean]("")
    override val shape: FlowShape[Boolean, Boolean] = FlowShape(in, out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
      val p = Promise[Done]
      (createLogic(inheritedAttributes, p), p.future)
    }

    def createLogic(inheritedAttributes: Attributes, promise: Promise[Done]): GraphStageLogic = new IdleGraphStageLogic(shape, init, factor) {
      override def onTimer(timerKey: Any): Unit =
        completeStage()

      override def preStart(): Unit = {
        super.preStart()
        promise.trySuccess(Done)
      }

      val handler = collect.fold(new IdleHandler[Boolean](in, out, activity))(c => new SetIdleTimeoutHandler[Boolean](in, out, activity, c))
      setHandlers(in, out, handler)
    }
  }

  def initializedTestFlow[In, Out, M](f: Graph[FlowShape[In, Out], Future[M]]) = {
    val ((pub, sub), fm) = testFlowMat(f)(Keep.both)
    Await.result(fm, Duration.Inf)
    (pub, sub)
  }

  "an IdleGraphStage initial parameters" should {
    "set timer at start if initialTimeout is defined" in {
      val (_, sub) = initializedTestFlow(flow(Some(1.second), 1.0, identity, None))
      time.advance(1.second)
      sub.request(1)
      sub.expectComplete()
    }

    "not set timer at start if initialTimeout is empty" in {
      val (pub, sub) = initializedTestFlow(flow(None, 1.0, identity, None))
      time.advance(1.second + 1.milli)
      pub.sendNext(true)
      sub.requestNext(true)
    }

    "do not change the initial delay, whatever the factor" in {
      val (_, sub) = initializedTestFlow(flow(Some(1.second), 5.0, identity, None))
      time.advance(1.second)
      sub.request(1)
      sub.expectComplete()
    }

    "not allow an infinite factor at materialization time" in {
      assertThrows[IllegalArgumentException](initializedTestFlow(flow(Some(1.second), Double.PositiveInfinity, identity, None)))
    }
  }

  "an IdleGraphStage idle handler" should {
    "reset the timer on activity" in {
      val (pub, sub) = initializedTestFlow(flow(Some(1.second), 1.0, identity, None))
      time.advance(1.second - 1.milli)
      pub.sendNext(true)
      sub.requestNext(true)
      time.advance(1.second - 1.milli)
      pub.sendNext(false)
      sub.requestNext(false)
    }

    "not reset the timer on not activity" in {
      val (pub, sub) = initializedTestFlow(flow(Some(1.second), 1.0, identity, None))
      time.advance(1.second - 1.milli)
      pub.sendNext(false)
      sub.requestNext(false)
      time.advance(200.millis)
      sub.expectComplete()
    }

    "have a factored timeout" in {
      val (pub, sub) = initializedTestFlow(flow(Some(1.second), 2, identity, None))
      time.advance(1.second - 1.milli)
      pub.sendNext(true)
      sub.requestNext(true)
      time.advance(2.seconds - 1.milli)
      pub.sendNext(false)
      sub.requestNext(false)
      time.advance(1.milli)
      sub.expectComplete()
    }
  }

  "an IdleGraphStage SetTimeoutHandler" should {
    "change the timeout value when specified" in {
      val (pub, sub) = initializedTestFlow(flow(Some(500.millis), 1.0, _ => true, Some { case true => Some(1.second) }))
      pub.sendNext(true)
      sub.requestNext(true)
      time.advance(1.second - 1.milli)
      pub.sendNext(false)
      sub.requestNext(false)
      time.advance(1.second)
      sub.expectComplete()
    }

    "cancel the timer when timeout is set to None" in {
      val (pub, sub) = initializedTestFlow(flow(Some(1.second), 1.0, _ => true, Some { case true => None }))
      pub.sendNext(true)
      sub.requestNext(true)
      time.advance(1.second)
      pub.sendNext(false)
      sub.requestNext(false)
    }
  }

  "two IdleGraphstages" should {
    "not interfere with each other" in {
      val (_, sub1) = initializedTestFlow(flow(Some(1.second), 1.0, _ => true, None))
      val (pub2, sub2) = initializedTestFlow(flow(Some(2.seconds), 1.0, identity, None))
      time.advance(1.second)
      sub1.request(1).expectComplete()
      pub2.sendNext(false)
      sub2.requestNext(false)
      time.advance(1.second)
      sub2.expectComplete()
    }
  }
}
