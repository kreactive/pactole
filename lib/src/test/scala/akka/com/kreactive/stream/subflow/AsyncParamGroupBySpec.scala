package akka.com.kreactive.stream.subflow

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestKit
import com.kreactive.stream.{FlowUtils, StreamTestKit}
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by cyrille on 07/02/2017.
  */
class AsyncParamGroupBySpec extends TestKit(ActorSystem("AsyncParamGroupBySpec")) with StreamTestKit with FlowUtils with WordSpecLike with MustMatchers {

  "an asyncParamGroupBy" should {
    "create a sink for every key" in {
      var hSink = ""
      def sink(h: String) = Flow[String].scan("")(_ + _).to(if (h == "h") Sink.foreach(hSink = _) else Sink.ignore)

      val pub = TestSource.probe[String].asyncParamGroupBy[String](Int.MaxValue, _.take(1), h => Future.successful(sink(h))).run()

      pub.
        sendNext("hello").
        sendNext("world").
        sendNext("whats").
        sendNext("hup").
        sendNext("waza")

      Thread.sleep(1000)
      
      hSink mustBe "hellohup"
    }
    "create a flow for every key" in {
      def flow(h: String) = Flow[String].scan("")(_ + _).drop(1)

      val (pub, sub) = testFlow(Flow[String].asyncParamGroupBy[String, String](Int.MaxValue, _.take(1), h => Future.successful(flow(h))).mergeSubstreams)

      pub.
        sendNext("hello").
        sendNext("world").
        sendNext("whats").
        sendNext("hup").
        sendNext("waza")

      sub.request(5).expectNextN(5).toSet mustBe Set("hello", "world", "worldwhats", "hellohup", "worldwhatswaza")
    }
    "create lazily a sink for every key" in {
      var hSink = ""
      def sink(h: String) = Flow[String].scan("")(_ + _).to(if (h == "h") Sink.foreach(hSink = _) else Sink.ignore)

      val pub = TestSource.probe[String].asyncParamGroupBy[String](Int.MaxValue, _.take(1), h => Future{
        Thread.sleep(1000)
        sink(h)
      }).run()

      pub.
        sendNext("hello").
        sendNext("world").
        sendNext("whats").
        sendNext("hup").
        sendNext("waza")

      Thread.sleep(3000)

      hSink mustBe "hellohup"
    }
    "create lazily a flow for every key" in {
      def flow(h: String) = Flow[String].scan("")(_ + _).drop(1)

      val (pub, sub) = testFlow(Flow[String].asyncParamGroupBy[String, String](Int.MaxValue, _.take(1), h => Future.successful(flow(h))).mergeSubstreams)

      pub.
        sendNext("hello").
        sendNext("world").
        sendNext("whats").
        sendNext("hup").
        sendNext("waza")

      sub.request(5).expectNextN(5).toSet mustBe Set("hello", "world", "worldwhats", "hellohup", "worldwhatswaza")
    }
  }

}
