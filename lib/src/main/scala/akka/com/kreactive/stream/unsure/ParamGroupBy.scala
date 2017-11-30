package akka.com.kreactive.stream.unsure

import akka.NotUsed
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.scaladsl.Source
import akka.stream.stage._

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
  * Created by cyrille on 20/03/2017.
  */
final class ParamGroupBy[T, K](val maxSubstreams: Int, val keyFor: T ⇒ K) extends GraphStage[FlowShape[T, (K, Source[T, NotUsed])]] {
  val in: Inlet[T] = Inlet("GroupBy.in")
  val out: Outlet[(K, Source[T, NotUsed])] = Outlet("GroupBy.out")
  override val shape: FlowShape[T, (K, Source[T, NotUsed])] = FlowShape(in, out)
  override def initialAttributes = DefaultAttributes.groupBy

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with OutHandler with InHandler {
    parent ⇒
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    private val activeSubstreamsMap = new java.util.HashMap[Any, SubstreamSource]()
    private val closedSubstreams = new java.util.HashSet[Any]()
    private var timeout: FiniteDuration = _
    private var substreamWaitingToBePushed: Option[SubstreamSource] = None
    private var nextElementKey: K = null.asInstanceOf[K]
    private var nextElementValue: T = null.asInstanceOf[T]
    private var _nextId = 0
    private val substreamsJustStared = new java.util.HashSet[Any]()
    private var firstPushCounter: Int = 0

    private def nextId(): Long = { _nextId += 1; _nextId }

    private def hasNextElement = nextElementKey != null

    private def clearNextElement(): Unit = {
      nextElementKey = null.asInstanceOf[K]
      nextElementValue = null.asInstanceOf[T]
    }

    private def tryCompleteAll(): Boolean = {
      //("tryCompleteAll")
      if (activeSubstreamsMap.isEmpty || (!hasNextElement && firstPushCounter == 0)) {
        for (value ← activeSubstreamsMap.values()) value.complete()
        completeStage()
        true
      } else false
    }

    private def fail(ex: Throwable): Unit = {
      //("fail")
      for (value ← activeSubstreamsMap.values()) value.fail(ex)
      failStage(ex)
    }

    private def needToPull: Boolean = {
      //("needToPull")
      !(hasBeenPulled(in) || isClosed(in) || hasNextElement)
    }

    override def preStart(): Unit =
      timeout = materializer.asInstanceOf[ActorMaterializer].settings.subscriptionTimeoutSettings.timeout

    override def onPull(): Unit = {
      substreamWaitingToBePushed match {
        case Some(substreamSource) ⇒
          //("dispatcher pulled; a subsource was stored")
          push(out, (substreamSource.key, Source.fromGraph(substreamSource.source)))
          scheduleOnce(substreamSource.key, timeout) // close this substream if it is not pulled fast enough
          substreamWaitingToBePushed = None
        case None ⇒
          if (hasNextElement) {
            //("dispatcher pulled; an element was stored")
            val subSubstreamSource = activeSubstreamsMap.get(nextElementKey)
            if (subSubstreamSource.isAvailable) {
              subSubstreamSource.push(nextElementValue)
              clearNextElement()
            }
          } else if (!hasBeenPulled(in)) {
            //("dispatcher pulled; nothing stored: pulling upstream")
            tryPull(in)
          } else {
            //("dispatcher pulled; nothing stored; upstream already pulled")
          }
      }
    }

    override def onUpstreamFailure(ex: Throwable): Unit = fail(ex)

    override def onDownstreamFinish(): Unit = {
      //("downstream finished")
      if (activeSubstreamsMap.isEmpty) completeStage() else setKeepGoing(true)
    }

    override def onPush(): Unit = try {
      //("parent.onPush")
      val elem = grab(in)
      val key = keyFor(elem)
      require(key != null, "Key cannot be null")
      val substreamSource = activeSubstreamsMap.get(key)
      if (substreamSource != null) {
        if (substreamSource.isAvailable) {
//          //("element pushed to dispatcher; substream present and available: pushing onto it")
          substreamSource.push(elem)
        }
        else {
          //(s"element pushed to dispatcher; substream  $key present but unavailable: storing element")
          nextElementKey = key
          nextElementValue = elem
        }
      } else {
        //(s"element pushed to dispatcher; substream $key present but unavailable: storing element")
        if (activeSubstreamsMap.size == maxSubstreams)
          fail(new IllegalStateException(s"Cannot open substream for key '$key': too many substreams open"))
        else if (closedSubstreams.contains(key) && !hasBeenPulled(in))
          pull(in)
        else {
          //("key not found yet. starting a new substream")
          runSubstream(key, elem)
        }
      }
    } catch {
      case NonFatal(ex) ⇒
        decider(ex) match {
          case Supervision.Stop                         ⇒ fail(ex)
          case Supervision.Resume | Supervision.Restart ⇒ if (!hasBeenPulled(in)) pull(in)
        }
    }

    override def onUpstreamFinish(): Unit = {
      //("psrent.onUpstreamFinish")
      if (!tryCompleteAll()) setKeepGoing(true)
    }

    private def runSubstream(key: K, value: T): Unit = {
      //("runSubstream")
      val substreamSource = new SubstreamSource("GroupBySource " + nextId, key, value)
      activeSubstreamsMap.put(key, substreamSource)
      firstPushCounter += 1
      if (isAvailable(out)) {
        //("pushing new substream")
        push(out, (key, Source.fromGraph(substreamSource.source)))
        scheduleOnce(key, timeout)
        substreamWaitingToBePushed = None
      } else {
        //("not ready to push. Keep it in store")
        setKeepGoing(true)
        substreamsJustStared.add(substreamSource)
        substreamWaitingToBePushed = Some(substreamSource)
      }
    }

    override protected def onTimer(timerKey: Any): Unit = {
      //(s"onTimer($timerKey)")
      val substreamSource = activeSubstreamsMap.get(timerKey)
      if (substreamSource != null) {
        substreamSource.timeout(timeout)
        closedSubstreams.add(timerKey)
        activeSubstreamsMap.remove(timerKey)
        if (isClosed(in)) tryCompleteAll()
      }
    }

    setHandlers(in, out, this)

    private class SubstreamSource(name: String, val key: K, var firstElement: T) extends SubSourceOutlet[T](name) with OutHandler {
      def firstPush(): Boolean = firstElement != null
      def hasNextForSubSource = hasNextElement && nextElementKey == key
      private def completeSubStream(): Unit = {
        //("subsource.completeSubstream")
        complete()
        activeSubstreamsMap.remove(key)
        closedSubstreams.add(key)
      }

      private def tryCompleteHandler(): Unit = {
        //("tryCompleteHandler")
        if (parent.isClosed(in) && !hasNextForSubSource) {
          completeSubStream()
          tryCompleteAll()
        }
      }

      override def onPull(): Unit = {
        //("subsource.onPull")
        cancelTimer(key)
        if (firstPush) {
          firstPushCounter -= 1
          push(firstElement)
          firstElement = null.asInstanceOf[T]
          substreamsJustStared.remove(this)
          if (substreamsJustStared.isEmpty) setKeepGoing(false)
        } else if (hasNextForSubSource) {
          push(nextElementValue)
          clearNextElement()
        } else if (needToPull) {
          pull(in)
          //(s"substream $key pulled; not first element; no element waiting; pulling")
        }
        else //(s"substream $key pulled; not first element; no element waiting; no need to pull")

        tryCompleteHandler()
      }

      override def onDownstreamFinish(): Unit = {
        //("subsource.onDownstreamFinish")
        if (hasNextElement && nextElementKey == key) clearNextElement()
        if (firstPush()) firstPushCounter -= 1
        completeSubStream()
        if (parent.isClosed(in)) tryCompleteAll() else if (needToPull) pull(in) else {
          //(s"substream $key cancelled; parent not closed; no need to pull")
        }
      }

      setHandler(this)
    }
  }

  override def toString: String = "GroupBy"

}
