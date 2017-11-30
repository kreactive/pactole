package com.kreactive.stream.fanout

import akka.NotUsed
import akka.stream.{FanOutShape2, Graph, UniformFanOutShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL}

object CollectFanout {
  /**
    * outputs the input stream on the first outlet, and the collected elements only on the second outlet
    * @param pf the partial partition which is defined on elements used for the second outlet,
    *           and how they are transformed before being pushed
    */
  def apply[A, B](pf: PartialFunction[A, B]): Graph[FanOutShape2[A, A, B], NotUsed] = GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    val broadcast: Broadcast[A] = Broadcast[A](2)

    val tee: UniformFanOutShape[A, A] = b.add(broadcast)
    val collectr = b.add(Flow[A].collect(pf))

    tee.out(1) ~> collectr.in

    new FanOutShape2[A, A, B](tee.in, tee.out(0), collectr.out)
  }

}
