package com.kreactive.stream.hub

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage._
import akka.{Done, NotUsed}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Created by cyrille on 29/11/2016.
  */
class DispatcherHub[A, B, Id](getId: A => Id, resize: A => B, bufferLogSize: Int) extends GraphStageWithMaterializedValue[SinkShape[A], Id => Source[B, NotUsed]] {
  val bufferSize = 1 << bufferLogSize
  require(bufferSize > 0, "Buffer size must be positive")
  require(bufferSize < 4096, "Buffer size larger then 4095 is not allowed")
  require((bufferSize & bufferSize - 1) == 0, "Buffer size must be a power of two")

  private val Mask = bufferSize - 1
  private val WheelMask = (bufferSize * 2) - 1

  val in: Inlet[A] = Inlet("BroadcastHub.in")
  override val shape: SinkShape[A] = SinkShape(in)

  // Half of buffer size, rounded up
  private[this] val DemandThreshold = (bufferSize / 2) + (bufferSize % 2)

  private sealed trait HubEvent

  private case object RegistrationPending extends HubEvent
  private final case class UnRegister(id: Long, listenFor: Id, previousOffset: Int, finalOffset: Int) extends HubEvent
  private final case class Advance(id: Long, listenFor: Id, previousOffset: Int) extends HubEvent
  private final case class NeedWakeup(id: Long, listenFor: Id, previousOffset: Int, currentOffset: Int) extends HubEvent

  private final case class Consumer(id: Long, listenFor: Id, callback: AsyncCallback[ConsumerEvent])

  private sealed trait HubState
  private case class Open(callbackFuture: Future[AsyncCallback[HubEvent]], registrations: List[Consumer]) extends HubState
  private case class Closed(failure: Option[Throwable]) extends HubState

  type QueueElement = Either[Done, B]
  private class BroadcastSinkLogic(_shape: Shape)
    extends GraphStageLogic(_shape) with InHandler {

    private[this] val callbackPromise: Promise[AsyncCallback[HubEvent]] = Promise()
    private[this] val noRegistrationsState = Open(callbackPromise.future, Nil)
    val state = new AtomicReference[HubState](noRegistrationsState)

    // Start from values that will almost immediately overflow. This has no effect on performance, any starting
    // number will do, however, this protects from regressions as these values *almost surely* overflow and fail
    // tests if someone makes a mistake.
    private[this] val tail = TrieMap.empty[Id, Int]
    private[this] val head = TrieMap.empty[Id, Int]
    /*
     * An Array with a published tail ("latest message") and a privately maintained head ("earliest buffered message").
     * Elements are published by simply putting them into the array and bumping the tail. If necessary, certain
     * consumers are sent a wakeup message through an AsyncCallback.
     */
    private[this] val queue = TrieMap.empty[Id, Array[QueueElement]]
    /* This is basically a classic Bucket Queue: https://en.wikipedia.org/wiki/Bucket_queue
     * (in fact, this is the variant described in the Optimizations section, where the given set
     * of priorities always fall to a range
     *
     * This wheel tracks the position of Consumers relative to the slowest ones. Every slot
     * contains a list of Consumers being known at that location (this might be out of date!).
     * Consumers from time to time send Advance messages to indicate that they have progressed
     * by reading from the broadcast queue. Consumers that are blocked (due to reaching tail) request
     * a wakeup and update their position at the same time.
     *
     */
    private[this] val consumerWheel = TrieMap.empty[Id, Array[List[Consumer]]]
    private[this] var activeConsumers = 0

    override def preStart(): Unit = {
      setKeepGoing(true)
      callbackPromise.success(getAsyncCallback[HubEvent](onEvent))
      pull(in)
    }

    def startId(id: Id) = {
      tail.put(id, Int.MaxValue)
      head.put(id, Int.MaxValue)
      queue.put(id, Array.ofDim[QueueElement](bufferSize))
      consumerWheel.put(id, Array.fill[List[Consumer]](bufferSize * 2)(Nil))
    }

    def dropId(id: Id) = {
      tail.remove(id)
      head.remove(id)
      queue.remove(id)
      consumerWheel.remove(id)
    }

    private def checkExists(id: Id) = if (tail.get(id).isEmpty) startId(id)

    // Cannot complete immediately if there is no space in the queue to put the completion marker
    override def onUpstreamFinish(): Unit = if (tail.keySet.forall(!isFull(_))) complete()

    override def onPush(): Unit = {
      val a = grab(in)
      val id = getId(a)
      checkExists(id)
      publish(id, resize(a))
      if (tail.keySet.forall(!isFull(_)))
        pull(in)
    }

    private def onEvent(ev: HubEvent): Unit = {
      ev match {
        case RegistrationPending ⇒
          state.getAndSet(noRegistrationsState).asInstanceOf[Open].registrations foreach { consumer ⇒
            val id = consumer.listenFor
            checkExists(id)
            val startFrom = head(id)
            activeConsumers += 1
            addConsumer(consumer, startFrom)
            consumer.callback.invoke(Initialize(startFrom))
          }

        case UnRegister(id, listenFor, previousOffset, finalOffset) ⇒
          activeConsumers -= 1
          val consumer = findAndRemoveConsumer(id, listenFor, previousOffset)
          if (activeConsumers == 0) {
            if (isClosed(in)) completeStage()
            else {
              dropId(listenFor)
              if (!hasBeenPulled(in)) pull(in)
            }
          } else checkUnblock(listenFor, previousOffset)
        case Advance(id, listenFor, previousOffset) ⇒
          val newOffset = previousOffset + DemandThreshold
          // Move the consumer from its last known offest to its new one. Check if we are unblocked.
          val consumer = findAndRemoveConsumer(id, listenFor, previousOffset)
          addConsumer(consumer, newOffset)
          checkUnblock(listenFor, previousOffset)
        case NeedWakeup(id, listenFor, previousOffset, currentOffset) ⇒
          // Move the consumer from its last known offest to its new one. Check if we are unblocked.
          val consumer = findAndRemoveConsumer(id, listenFor, previousOffset)
          addConsumer(consumer, currentOffset)

          // Also check if the consumer is now unblocked since we published an element since it went asleep.
          if (currentOffset != tail(listenFor)) consumer.callback.invoke(Wakeup)
          checkUnblock(listenFor, previousOffset)
      }
    }

    // Producer API
    // We are full if the distance between the slowest (known) consumer and the fastest (known) consumer is
    // the buffer size. We must wait until the slowest either advances, or cancels.
    private def isFull(id: Id): Boolean = tail(id) - head(id) == bufferSize

    def onUpstreamClose(closeMessage: ConsumerEvent): Unit = {

    }
    override def onUpstreamFailure(ex: Throwable): Unit = {
      val failMessage = HubCompleted(Some(ex))

      // Notify pending consumers and set tombstone
      state.getAndSet(Closed(Some(ex))).asInstanceOf[Open].registrations foreach { consumer ⇒
        consumer.callback.invoke(failMessage)
      }

      // Notify registered consumers
      consumerWheel.iterator.flatMap(_._2.iterator).flatMap(_.iterator) foreach { consumer ⇒
        consumer.callback.invoke(failMessage)
      }
      failStage(ex)
    }

    /*
     * This method removes a consumer with a given ID from the known offset and returns it.
     *
     * NB: You cannot remove a consumer without knowing its last offset! Consumers on the Source side always must
     * track this so this can be a fast operation.
     */
    private def findAndRemoveConsumer(id: Long, listenFor: Id, offset: Int): Consumer = {
      // TODO: Try to eliminate modulo division somehow...
      val wheelSlot = offset & WheelMask
      var consumersInSlot = consumerWheel(listenFor)(wheelSlot)
      //debug(s"consumers before removal $consumersInSlot")
      var remainingConsumersInSlot: List[Consumer] = Nil
      var removedConsumer: Consumer = null

      while (consumersInSlot.nonEmpty) {
        val consumer = consumersInSlot.head
        if (consumer.id != id) remainingConsumersInSlot = consumer :: remainingConsumersInSlot
        else removedConsumer = consumer
        consumersInSlot = consumersInSlot.tail
      }
      consumerWheel(listenFor)(wheelSlot) = remainingConsumersInSlot
      removedConsumer
    }

    /*
     * After removing a Consumer from a wheel slot (because it cancelled, or we moved it because it advanced)
     * we need to check if it was blocking us from advancing (being the slowest).
     */
    private def checkUnblock(id: Id, offsetOfConsumerRemoved: Int): Unit = {
      if (unblockIfPossible(id, offsetOfConsumerRemoved)) {
        if (isClosed(in)) complete()
        else if (!hasBeenPulled(in)) pull(in)
      }
    }

    private def unblockIfPossible(id: Id, offsetOfConsumerRemoved: Int): Boolean = {
      var unblocked = false
      if (offsetOfConsumerRemoved == head(id)) {
        // Try to advance along the wheel. We can skip any wheel slots which have no waiting Consumers, until
        // we either find a nonempty one, or we reached the end of the buffer.
        while (consumerWheel(id)(head(id) & WheelMask).isEmpty && head(id) != tail(id)) {
          queue(id)(head(id) & Mask) = null
          head(id) += 1
          unblocked = true
        }
      }
      unblocked
    }

    private def addConsumer(consumer: Consumer, offset: Int): Unit = {
      val slot = offset & WheelMask
      consumerWheel(consumer.listenFor)(slot) = consumer :: consumerWheel(consumer.listenFor)(slot)
    }

    /*
     * Send a wakeup signal to all the Consumers at a certain wheel index. Note, this needs the actual index,
     * which is offset modulo (bufferSize + 1).
     */
    private def wakeupIdx(id: Id, idx: Int): Unit = {
      val itr = consumerWheel(id)(idx).iterator
      while (itr.hasNext) itr.next().callback.invoke(Wakeup)
    }

    private def complete(): Unit = {
      tail.foreach { case (id, t) =>

        val idx = t & Mask
        val wheelSlot = t & WheelMask
        queue(id)(idx) = Left(Done)
        wakeupIdx(id, wheelSlot)
        tail(id) = t + 1
      }
      if (activeConsumers == 0) {
        val completedMessage = HubCompleted(None)
        // Notify pending consumers and set tombstone
        state.getAndSet(Closed(None)).asInstanceOf[Open].registrations foreach { consumer ⇒
          consumer.callback.invoke(completedMessage)
        }

        // Existing consumers have already consumed all elements and will see completion status in the queue
        if (tail.isEmpty) completeStage()
      }
    }

    private def publish(id: Id, elem: B): Unit = {
      val idx = tail(id) & Mask
      val wheelSlot = tail(id) & WheelMask
      queue(id)(idx) = Right(elem)
      // Publish the new tail before calling the wakeup
      tail(id) = tail(id) + 1
      wakeupIdx(id, wheelSlot)
    }

    // Consumer API
    def poll(id: Id, offset: Int): QueueElement = {
      if (offset == tail(id)) null
      else queue(id)(offset & Mask)
    }

    setHandler(in, this)
  }

  private sealed trait ConsumerEvent
  private object Wakeup extends ConsumerEvent
  private final case class HubCompleted(failure: Option[Throwable]) extends ConsumerEvent
  private final case class Initialize(offset: Int) extends ConsumerEvent

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Id => Source[B, NotUsed]) = {
    val idCounter = new AtomicLong()

    val logic = new BroadcastSinkLogic(shape)

    def source(listenFor: Id) = new GraphStage[SourceShape[B]] {
      val out: Outlet[B] = Outlet("BroadcastHub.out")
      override val shape: SourceShape[B] = SourceShape(out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {
        private[this] var untilNextAdvanceSignal = DemandThreshold
        private[this] val id = idCounter.getAndIncrement()
        private[this] var offsetInitialized = false
        private[this] var hubCallback: AsyncCallback[HubEvent] = _

        /*
         * We need to track our last offset that we published to the Hub. The reason is, that for efficiency reasons,
         * the Hub can only look up and move/remove Consumers with known wheel slots. This means that no extra hash-map
         * is needed, but it also means that we need to keep track of both our current offset, and the last one that
         * we published.
         */
        private[this] var previousPublishedOffset = 0
        private[this] var offset = 0

        override def preStart(): Unit = {
          val callback = getAsyncCallback(onCommand)

          val onHubReady: Try[AsyncCallback[HubEvent]] ⇒ Unit = {
            case Success(callback) ⇒
              hubCallback = callback
              if (isAvailable(out) && offsetInitialized) onPull()
              callback.invoke(RegistrationPending)
            case Failure(ex) ⇒
              failStage(ex)
          }

          @tailrec def register(): Unit = {
            logic.state.get() match {
              case Closed(Some(ex)) ⇒ failStage(ex)
              case Closed(None)     ⇒ completeStage()
              case previousState @ Open(callbackFuture, registrations) ⇒
                val newRegistrations = Consumer(id, listenFor, callback) :: registrations
                if (logic.state.compareAndSet(previousState, Open(callbackFuture, newRegistrations))) {
                  callbackFuture.onComplete(getAsyncCallback(onHubReady).invoke)(materializer.executionContext)
                } else register()
            }
          }

          /*
           * Note that there is a potential race here. First we add ourselves to the pending registrations, then
           * we send RegistrationPending. However, another downstream might have triggered our registration by its
           * own RegistrationPending message, since we are in the list already.
           * This means we might receive an onCommand(Initialize(offset)) *before* onHubReady fires so it is important
           * to only serve elements after both offsetInitialized = true and hubCallback is not null.
           */
          register()

        }

        override def onPull(): Unit = {
          if (offsetInitialized && (hubCallback ne null)) {
            val elem = logic.poll(listenFor, offset)

            elem match {
              case null ⇒
                hubCallback.invoke(NeedWakeup(id, listenFor, previousPublishedOffset, offset))
                previousPublishedOffset = offset
                untilNextAdvanceSignal = DemandThreshold
              case Left(Done) ⇒
                completeStage()
              case Right(b) ⇒
                push(out, b)
                offset += 1
                untilNextAdvanceSignal -= 1
                if (untilNextAdvanceSignal == 0) {
                  untilNextAdvanceSignal = DemandThreshold
                  val previousOffset = previousPublishedOffset
                  previousPublishedOffset += DemandThreshold
                  hubCallback.invoke(Advance(id, listenFor, previousOffset))
                }
            }
          }
        }

        override def postStop(): Unit = {
          if (hubCallback ne null)
            hubCallback.invoke(UnRegister(id, listenFor, previousPublishedOffset, offset))
        }

        private def onCommand(cmd: ConsumerEvent): Unit = cmd match {
          case HubCompleted(Some(ex)) ⇒ failStage(ex)
          case HubCompleted(None)     ⇒ completeStage()
          case a: HubCompleted ⇒ ()
          case Wakeup ⇒
            if (isAvailable(out)) onPull()
          case Initialize(initialOffset) ⇒
            offsetInitialized = true
            previousPublishedOffset = initialOffset
            offset = initialOffset
            if (isAvailable(out) && (hubCallback ne null)) onPull()
        }

        setHandler(out, this)
      }
    }

    (logic, id => Source.fromGraph(source(id)))
  }
}

object DispatcherHub {
  type DispatchSink[A, B, Id] = Sink[A, Id => Source[B, NotUsed]]
  def sink[A] = new TypedDispatcherHub[A] {}

  trait TypedDispatcherHub[A] {
    def apply[Id](getId: A => Id): DispatchSink[A, A, Id] = apply(getId, x => x)
    def apply[Id](getId: A => Id, buffer: Int): DispatchSink[A, A, Id] = apply(getId, x => x, buffer)
    def apply[Id, B](getId: A => Id, resize: A => B): DispatchSink[A, B, Id] = apply(getId, resize, 11)
    def apply[Id, B](getId: A => Id, resize: A => B, buffer: Int): DispatchSink[A, B, Id] = Sink.fromGraph(new DispatcherHub[A, B, Id](getId, resize, buffer))
    def apply[Id, B](getId: A => Id, resize: A => B, queueBuffer: Int, maxQueues: Byte, queueSelector: Id => Byte): DispatchSink[A, B, Id] =
      Sink.fromGraph(new DispatcherHub[A, (B, Id), Byte](a => queueSelector(getId(a)), a => (resize(a), getId(a)), queueBuffer)).
        mapMaterializedValue(byteSourceGen => (id: Id) => byteSourceGen(queueSelector(id)).collect{
          case (b, `id`) => b
        })
  }
}