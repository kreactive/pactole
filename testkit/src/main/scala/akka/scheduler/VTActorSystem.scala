package akka.scheduler

import akka.actor.setup.ActorSystemSetup
import akka.actor.{ActorSystem, ActorSystemImpl, BootstrapSetup, Props, Scheduler}
import akka.testkit.TestKit
import com.miguno.akka.testing.VirtualTime
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext
/**
  * Created by cyrille on 26/01/2017.
  */
class VTActorSystem(
                     name:                String,
                     val time:                VirtualTime,
                     applicationConfig:       Config,
                     classLoader:             ClassLoader,
                     defaultExecutionContext: Option[ExecutionContext],
                     guardianProps:       Option[Props]) extends
  ActorSystemImpl(name, applicationConfig, classLoader, defaultExecutionContext, guardianProps, ActorSystemSetup(BootstrapSetup(Some(classLoader), Some(applicationConfig), defaultExecutionContext))){
  override def createScheduler(): Scheduler = time.scheduler

  override val scheduler: Scheduler = time.scheduler
}

object VTActorSystem {
  def apply(name: String,
            time: VirtualTime,
            config: Option[Config] = None,
            classLoader: Option[ClassLoader] = None,
            defaultExecutionContext: Option[ExecutionContext] = None
           ): VTActorSystem = {
    val cl = classLoader.getOrElse(ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    new VTActorSystem(name, time, appConfig, cl, defaultExecutionContext, None).start()
  }

  def apply(name: String, time: VirtualTime, config: Config): VTActorSystem = apply(name, time, Some(config))
}

class VTTestKit(vtSystem: VTActorSystem) extends TestKit(vtSystem) {
  def this(name: String, time: VirtualTime) = this(VTActorSystem(name, time))
  val time = vtSystem.time
}
