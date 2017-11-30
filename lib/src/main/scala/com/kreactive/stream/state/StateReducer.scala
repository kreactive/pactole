package com.kreactive.stream.state

import akka.NotUsed
import akka.stream.scaladsl.Flow

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by cyrille on 13/12/2016.
  */
object StateReducer {
  def flow[State, I](reducer: PartialFunction[(Option[State], I), Future[Option[State]]])(implicit ec: ExecutionContext): Flow[I, (State, I), NotUsed] =
    Flow[I].scanAsync[(Option[State], Option[I])]((None, None)){
      case ((stateO, _), i) => reducer.lift((stateO, i)).getOrElse(Future.successful(None)).map(newStateO => (newStateO.orElse(stateO), Some(i)))
    }.drop(1) //remove zero element
      .collect{
      case (Some(s), Some(i)) => (s, i)
    }
}
