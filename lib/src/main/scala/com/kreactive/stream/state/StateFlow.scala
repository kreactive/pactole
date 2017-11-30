package com.kreactive.stream.state

import akka.NotUsed
import akka.stream.scaladsl.Flow

/**
  * Created by cyrille on 05/12/2016.
  */
object StateFlow {
  def apply[State, I](initState: I => Option[State], setOnlyOnce: Boolean = false): Flow[I, (State, I), NotUsed] = {
    Flow[I].scan[Option[(State, I)]](None){
      case (stateO, i) =>
        val oldState = stateO.map(_._1)
        oldState.filter(_ => setOnlyOnce).orElse(initState(i)).orElse(oldState).map((_, i))
    }.collect{case Some((state, i)) => (state, i)}
  }
}