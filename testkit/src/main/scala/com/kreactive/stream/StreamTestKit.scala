package com.kreactive.stream

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.testkit.TestKit

import scala.concurrent.{ExecutionContext, Future, Promise}


trait StreamTestKit {
  self: TestKit =>

  implicit def systemToMaterializer(implicit system: ActorSystem) = ActorMaterializer()

  implicit def systemToDispatcher(implicit system: ActorSystem): ExecutionContext = system.dispatcher

  def testBidi[I1, O1, I2, O2, M](bidi: Graph[BidiShape[I1, O1, I2, O2], M])(implicit system: ActorSystem) =
    Flow.fromSinkAndSourceMat(TestSink.probe[O2], TestSource.probe[I1])(Keep.both).
      join(bidi).
      joinMat(Flow.fromSinkAndSourceMat(TestSink.probe[O1], TestSource.probe[I2])(Keep.both)) {
        case ((o2, i1), (o1, i2)) => (i1, o1, i2, o2)
      }.run()

  def testFlowMat[I, O, M, Mat](flow: Graph[FlowShape[I, O], M])(combine: ((TestPublisher.Probe[I], TestSubscriber.Probe[O]), M) => Mat)(implicit system: ActorSystem) =
    TestSource.probe[I].viaMat(flow)(Keep.both).toMat(TestSink.probe[O]) {
      case ((pub, m), sub) => combine((pub, sub), m)
    }.run()

  def testFlow[I, O, M](flow: Graph[FlowShape[I, O], M])(implicit system: ActorSystem) = testFlowMat(flow)(Keep.left)

  def peekMat[In, M](graph: Sink[In, M]): (Future[M], Sink[In, M]) = {
    val p = Promise[M]
    val newGraph = graph.mapMaterializedValue{ mat =>
      p.trySuccess(mat)
      mat
    }
    (p.future, newGraph)
  }
  def peekMat[Out, M](graph: Source[Out, M]): (Future[M], Source[Out, M]) = {
    val p = Promise[M]
    val newGraph = graph.mapMaterializedValue{ mat =>
      p.trySuccess(mat)
      mat
    }
    (p.future, newGraph)
  }
  def peekMat[In, Out, M](graph: Flow[In, Out, M]): (Future[M], Flow[In, Out, M]) = {
    val p = Promise[M]
    val newGraph = graph.mapMaterializedValue{ mat =>
      p.trySuccess(mat)
      mat
    }
    (p.future, newGraph)
  }
}
