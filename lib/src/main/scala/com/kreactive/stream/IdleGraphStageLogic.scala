package com.kreactive.stream

import akka.stream.stage.{InHandler, OutHandler, TimerGraphStageLogic}
import akka.stream.{Inlet, Outlet, Shape}

import scala.concurrent.duration.FiniteDuration

/**
  * A GraphStageLogic to deal with dynamic idle timeouts
  * @param shape
  * @param initTimeout the initial timeout. The timeout is given as an Option[FiniteDuration] so that while it is set to None, no check is done.
  *                    Once set to some duration, it will wait the given duration and then call onTimer(), unless an activity occurred
  * @param factor A factor on timeout, not used on initTimeout.
  */
class IdleGraphStageLogic(shape: Shape, initTimeout: Option[FiniteDuration], factor: Double = 1.0) extends TimerGraphStageLogic(shape) {
  object IdleTimer
  require(!factor.isInfinite, "Cannot have an infinite factor")
  protected var timeout = initTimeout

  private def onActivity(): Unit = timeout.foreach { t =>
    scheduleOnce(IdleTimer, (t * factor).asInstanceOf[FiniteDuration])
  }

  override def preStart(): Unit = timeout.foreach(scheduleOnce(IdleTimer, _))

  /**
    * A handler that simply pulls and push, and checks if an activity occurred
    * @param in  the port to pull
    * @param out the port on which to push
    * @param activity a predicate to check if the incoming element is considered an "activity"
    */
  class IdleHandler[P](in: Inlet[P], out: Outlet[P], activity: P => Boolean = (_: P) => true) extends InHandler with OutHandler {
    override def onPush(): Unit = {
      val elem = grab(in)
      if (activity(elem))
        onActivity()
      push(out, elem)
    }
    override def onPull(): Unit = if (!hasBeenPulled(in)) pull(in)
    override def onUpstreamFinish(): Unit = complete(out)
    override def onDownstreamFinish(): Unit = cancel(in)
  }

  /**
    * A handler that set the timeout according to incoming elements
    * @param in  the port to pull
    * @param out the port on which to push
    * @param collect a partial function that resets the timeout when it is defined on the incoming element.
    *                If its value is None, cancels the pre-existing timer; otherwise, resets it to the given value.
    * @param activity a predicate to check if the incoming element is considered an "activity".
    *                 It should be `true` for all values where `collect` is defined.
    */
  class SetIdleTimeoutHandler[P](
                                  in: Inlet[P],
                                  out: Outlet[P],
                                  activity: P => Boolean = (_: P) => true,
                                  collect: PartialFunction[P, Option[FiniteDuration]]
                                ) extends IdleHandler(in, out, activity) {
    override def onPush(): Unit = {
      val elem = grab(in)
      collect.lift(elem).foreach {
        timeout = _
      }
      if (timeout.isEmpty) cancelTimer(IdleTimer)
      if (activity(elem)) onActivity()
      if (!isClosed(out)) push(out, elem)
    }
  }
}
