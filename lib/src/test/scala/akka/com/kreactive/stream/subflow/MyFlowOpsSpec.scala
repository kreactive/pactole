package akka.com.kreactive.stream.subflow

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, DelayOverflowStrategy}
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source, SubFlow}
import akka.testkit.TestKit
import com.kreactive.stream.{FlowUtils, StreamTestKit}
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by cyrille on 16/03/2017.
  */
class MyFlowOpsSpec extends TestKit(ActorSystem("MyFlowOpsSpec")) with WordSpecLike with MustMatchers with FlowUtils with StreamTestKit {

  val rand = new Random(1236894136)
  "a paramGroupBy" should {
    "not break under pressure" in {
      val N = 10000L
      val nbFlows = 10000
      val smoothFlow = Flow[Int].map(_.toLong)
      type F[+O] = Source[O, NotUsed]
      def d = rand.nextInt(100).millis
      val source: Source[Int, NotUsed] = Source(Stream.from(0)).take(N)
      val paramSubflow: SubFlow[Long, NotUsed, F, RunnableGraph[NotUsed]] =
          source.paramGroupBy[Int, Int](nbFlows, _ % nbFlows, _ => Flow[Int].delay(d, DelayOverflowStrategy.backpressure)).via(smoothFlow)
      val subflow: SubFlow[Long, NotUsed, F, RunnableGraph[NotUsed]] =
          source.groupBy[Int](nbFlows, _ % nbFlows).delay(d, DelayOverflowStrategy.backpressure).via(smoothFlow)
      val res = paramSubflow.mergeSubstreams.runWith(Sink.seq)
//      val timeouted = Future.firstCompletedOf(Seq(Future[Done]{
//        Thread.sleep(60000)
//        throw new Exception("")
//      }, res))
      Await.result(res, Duration.Inf).toSet mustBe (0l until N).toSet
    }
  }
}
