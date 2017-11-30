package com.kreactive.stream.fanout

import akka.NotUsed
import akka.stream.{FanOutShape2, Graph}
import akka.stream.scaladsl.{Flow, GraphDSL, Partition}

/**
  * partitions Either[A, B] values on whether they are left or right
  */
object EitherFanout {
  def apply[A, B]: Graph[FanOutShape2[Either[A, B], A, B], NotUsed] = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val part = b.add(Partition[Either[A, B]](2, _.right.toOption.fold(0)(_ => 1)).async)
    val collA = b.add(Flow[Either[A, B]].collect{case Left(a) => a})
    val collB = b.add(Flow[Either[A, B]].collect{case Right(bb) => bb})

    part.out(0) ~> collA.in
    part.out(1) ~> collB.in

    new FanOutShape2(part.in, collA.out ,collB.out)
  }
}
