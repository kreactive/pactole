package com.kreactive.stream.fanin

import akka.NotUsed
import akka.stream.{FanInShape2, Graph}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge}

/**
  * Created by cyrille on 14/12/2016.
  */
object EitherMerge {
  def apply[A, B]: Graph[FanInShape2[A, B, Either[A, B]], NotUsed] = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[Either[A, B]](2))
    val capsA = b.add(Flow[A].map(Left(_)))
    val capsB = b.add(Flow[B].map(Right(_)))

    capsA.out ~> merge.in(0)
    capsB.out ~> merge.in(1)

    new FanInShape2(capsA.in, capsB.in, merge.out)
  }
}
