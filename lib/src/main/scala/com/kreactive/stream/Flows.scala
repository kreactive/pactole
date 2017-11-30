package com.kreactive.stream

import akka.NotUsed
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Created by cyrille on 16/12/2016.
  */
object Flows {
  def actor[In, Out: ClassTag](actorRef: ActorRef, onComplete: Any = PoisonPill)(implicit executor: ExecutionContext, timeout: Timeout): Flow[In, Out, NotUsed] = {
    def actorFunc(in: In): Future[Out] = (actorRef ? in).mapTo[Out]
    Flow[In].
      mapAsync(1)(actorFunc).
      alsoToMat(Sink.ignore)(Keep.right).
      mapMaterializedValue{ fd =>
        fd.foreach(_ => actorRef ! onComplete)
        NotUsed
      }
  }

  /*def dynamicActor[In, Out: ClassTag](actorRef: In => ActorRef, onComplete: Any = PoisonPill)(implicit executor: ExecutionContext, timeout: Timeout): Flow[In, Out, Future[NotUsed]] =
    lazyInit[In, Out, NotUsed](in => Future.successful(actor(actorRef(in), onComplete)), () => NotUsed)

  /**
    * Flow equivalent for [[akka.stream.scaladsl.Sink.lazyInit]]. However, it uses actorRef ligature between flows and should probably not be used.
    */
  def lazyInit[In, Out, M](flowFactory: In => Future[Flow[In, Out, M]], fallback: () => M)(implicit executor: ExecutionContext): Flow[In, Out, Future[M]] =  {
    val source = Source.actorRef[Out](100, OverflowStrategy.dropTail)
    def sinkFactory(either: Either[Promise[ActorRef], In]): Future[Sink[Either[Promise[ActorRef], In], Future[M]]] = {
      either.left.get.future.map { actor =>
          val keepRight = Flow[Either[Promise[ActorRef], In]].collect{case Right(r) => r}
          val sinkFromActor = Sink.actorRef[Out](actor, PoisonPill)
          keepRight.toMat(Sink.lazyInit(flowFactory(_).map(_.to(sinkFromActor)), fallback))(Keep.right)
      }
    }
    implicit val eitherOrder = new Ordering[Either[Promise[ActorRef], In] with Product with Serializable] {
      override def compare(x: Either[Promise[ActorRef], In] with Product with Serializable, y: Either[Promise[ActorRef], In] with Product with Serializable): Int = (x, y) match {
        case (Left(_), Right(_)) => -1
        case (Right(_), Left(_)) => 1
        case _ => 0
      }

    }
    val promiseSource =
      Source.single(Promise[ActorRef])
    val addPromise = Flow[In].map(Right(_)).mergeSorted(promiseSource.map(Left(_)))
    val getFirstPromise =
      Flow[Either[Promise[ActorRef], In]].collect{case Left(p) => p}.toMat(Sink.head)(Keep.right)
    val sink: Sink[In, (Future[Promise[ActorRef]], Future[M])] =
      addPromise.alsoToMat(getFirstPromise)(Keep.right).toMat(
        Sink.lazyInit[Either[Promise[ActorRef], In], Future[M]](sinkFactory, () => Future.successful(fallback())))((fp, ffm) => (fp, ffm.flatMap(fm => fm)))

    Flow.fromSinkAndSourceMat(sink, source){
      case ((fp, fm), a) => fp.flatMap{ p =>
        p.success(a)
        fm
      }
    }
  }*/
}