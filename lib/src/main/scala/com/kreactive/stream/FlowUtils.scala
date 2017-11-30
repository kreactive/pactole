package com.kreactive.stream

import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, FlowOps, GraphDSL, Keep, Sink, Source, Zip}
import akka.com.kreactive.stream.subflow.MyFlowOps

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

trait FlowUtils {

  implicit class MyFlow[In, Out, Mat](flow: Flow[In, Out, Mat]) {

    def keepInput: Flow[In, (In, Out), Mat] = Flow.fromGraph(GraphDSL.create(flow) { implicit b =>
      current =>
        import GraphDSL.Implicits._

        val bc = b.add(Broadcast[In](2))
        val zip = b.add(Zip[In, Out])

        bc.out(0) ~> zip.in0
        bc.out(1) ~> current ~> zip.in1
        FlowShape(bc.in, zip.out)
    })

    def toSourceAndSink: (Source[Out, Mat], Sink[In, Mat]) =  {
      val sink =  flow to Sink.ignore
      val source = Source.empty.viaMat(flow)(Keep.right)
      (source, sink)
    }
  }

  implicit def flowOpsMat[Out, Mat](src: FlowOps[Out, Mat])(implicit executor: ExecutionContext, mat: Materializer): MyFlowOps[Out, Mat, src.type] =
    new MyFlowOps(src: src.type)
}

object FlowUtils extends FlowUtils