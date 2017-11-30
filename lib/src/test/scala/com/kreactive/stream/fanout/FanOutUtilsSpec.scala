package com.kreactive.stream
package fanout

import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, RunnableGraph}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.{ClosedShape, FanOutShape2, Graph}
import akka.testkit.TestKit
import org.scalatest.{MustMatchers, WordSpecLike}

/**
  * Created by cyrille on 14/12/2016.
  */
class FanOutUtilsSpec extends TestKit(ActorSystem("FanOutUtils")) with WordSpecLike with MustMatchers with StreamTestKit {

  def testFanOut[I, O0, O1](fanout: Graph[FanOutShape2[I, O0, O1], _]) =
    RunnableGraph.fromGraph(GraphDSL.create(TestSource.probe[I], TestSink.probe[O0], TestSink.probe[O1])((i, o, O) => (i, o, O)) { implicit b =>(i, o0, o1) =>
      import GraphDSL.Implicits._

      val f = b.add(fanout)
      i ~> f.in
      f.out0 ~> o0
      f.out1 ~> o1
      ClosedShape
    }).run()

  "an eitherFanOut" should {
    "complete on both outlets when input stream completes" in {
      val (in, outA, outB) = testFanOut(EitherFanout[String, Int])

      in.sendNext(Right(2)).sendComplete()
      outA.request(1).expectComplete()
      outB.requestNext(2).expectComplete()
    }
  }
}
