package com.kreactive.stream.state

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.GraphDSL
import com.kreactive.stream.fanin.EitherMerge

object StateZip {
  def apply[State, I](setOnlyOnce: Boolean = false): Graph[FanInShape2[State, I, (State, I)], NotUsed] = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(EitherMerge[State, I].async)
    val stateFlow = b.add(StateFlow[State, Either[State, I]](_.left.toOption, setOnlyOnce).collect{
      case (state, Right(i)) => (state, i)
    })

    merge.out ~> stateFlow.in

    new FanInShape2(merge.in0, merge.in1, stateFlow.out)

  }
}