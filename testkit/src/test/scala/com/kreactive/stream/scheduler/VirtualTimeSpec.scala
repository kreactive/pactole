package com.kreactive.stream.scheduler

import akka.scheduler.VTTestKit
import com.kreactive.stream.StreamTestKit
import com.miguno.akka.testing.VirtualTime
import org.scalatest.{MustMatchers, WordSpecLike}

import scala.concurrent.duration._
/**
  * Created by cyrille on 30/01/2017.
  */
class VirtualTimeSpec extends VTTestKit("VirtualTimerSpec", new VirtualTime) with WordSpecLike with MustMatchers with StreamTestKit{

  "a virtual timer system" should {
    "expose a fake timer" in {
      var p: Boolean = false
      system.scheduler.scheduleOnce(1.second){p = true}
      time.advance(1.second - 1.milli)
      if (p) fail("should not be completed yet.")
      Thread.sleep(1000)
      if (p) fail("should not be completed yet, even if one real second passed.")
      time.advance(1.milli)
      if (!p) fail("should be completed, by now")
    }
  }
}
