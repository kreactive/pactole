package akka.com.kreactive.stream.subflow

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.impl.SubFlowImpl
import akka.stream.impl.fusing.FlattenMerge
import akka.stream.scaladsl.{Flow, FlowOps, Sink, Source, SubFlow}
import akka.com.kreactive.stream.unsure.ParamGroupBy

import scala.concurrent.{ExecutionContext, Future}

class MyFlowOps[Out, Mat, FO <: FlowOps[Out, Mat]](val flowOps: FO)(implicit executor: ExecutionContext, mat: Materializer) {

  type F[+T] = FO#Repr[T]
  type C = FO#Closed
  /**
    * Greatly inspired by [[akka.stream.scaladsl.FlowOps.groupBy]]. Adds parametrization of the subflows by the key, and asynchronicity
    */
  def asyncParamGroupBy[K, T](maxSubstreams: Int, f: Out ⇒ K, flowFactory: K => Future[Flow[Out, T, _]]): SubFlow[T, Mat, F, C] = {
    val merge = new SubFlowImpl.MergeBack[T, flowOps.Repr] {
      override def apply[U](subflow: Flow[T, U, NotUsed], breadth: Int): flowOps.Repr[U] = {
        val sourceFlow: flowOps.Repr[Source[T, NotUsed]] = flowOps.via(new ParamGroupBy(maxSubstreams, f))
          .mapAsync(1){
            case (key, source) =>
              flowFactory(key).map(source.via(_))
          }
        sourceFlow.map(_.via(subflow)).via(new FlattenMerge[U, NotUsed](breadth))
      }
    }
    val finish: (Sink[T, NotUsed]) ⇒ C = s ⇒
      flowOps.via(new ParamGroupBy(maxSubstreams, f))
        .mapAsync(1){
          case (key, source) =>
            flowFactory(key).map(source.via(_))
        }
        .to(Sink.foreach(_.runWith(s)))
    new SubFlowImpl(Flow[T], merge, finish)
  }

  def asyncParamGroupBy[K](maxSubstreams: Int, f: Out ⇒ K, sinkFactory: K => Future[Sink[Out, _]]): C =
    flowOps.via(new ParamGroupBy(maxSubstreams, f))
      .mapAsync(1){
        case (key, source) =>
          sinkFactory(key).map(source.to(_))
      }.to(Sink.foreach(_.run()))

  def paramGroupBy[K, T](maxSubstreams: Int, f: Out ⇒ K, flowFactory: K => Flow[Out, T, _]): SubFlow[T, Mat, F, C] ={
    val merge = new SubFlowImpl.MergeBack[T, flowOps.Repr] {
      override def apply[U](subflow: Flow[T, U, NotUsed], breadth: Int): flowOps.Repr[U] = {
        val sourceFlow: flowOps.Repr[Source[T, NotUsed]] = flowOps.via(new ParamGroupBy(maxSubstreams, f))
          .map{
            case (key, source) =>
              source.via(flowFactory(key))
          }
        sourceFlow.map(_.via(subflow)).via(new FlattenMerge[U, NotUsed](breadth))
      }
    }
    val finish: (Sink[T, NotUsed]) ⇒ C = s ⇒
      flowOps.via(new ParamGroupBy(maxSubstreams, f))
        .map{
          case (key, source) =>
            source.via(flowFactory(key))
        }
        .to(Sink.foreach(_.runWith(s)))
    new SubFlowImpl(Flow[T], merge, finish)
  }

  def paramGroupBy[K](maxSubstreams: Int, f: Out ⇒ K, sinkFactory: K => Sink[Out, _]): C =
    flowOps.via(new ParamGroupBy(maxSubstreams, f))
      .map{
        case (key, source) =>
          source.to(sinkFactory(key))
      }
      .to(Sink.foreach(_.run()))
}