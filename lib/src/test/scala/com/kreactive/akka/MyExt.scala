package com.kreactive.akka

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}

/**
  * Created by cyrille on 18/01/2017.
  */
trait MyExt extends Extension {
  def hello: String
}

class MyExt1(system: ExtendedActorSystem) extends MyExt {
  val hello: String = "MyExt1"
}

case object MyExt1 extends ExtensionId[MyExt1] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): MyExt1 = new MyExt1(system)
  override def lookup(): ExtensionId[_ <: Extension] = this
}

case object MyExt2 extends ExtensionId[MyExt] with ExtensionIdProvider with MyExt {
  override def createExtension(system: ExtendedActorSystem): MyExt = this
  override def lookup(): ExtensionId[_ <: Extension] = this
  override def hello: String = "MyExt2"
}

case object MyExt extends MultiImplementedExtension[MyExt] {
  /**
    * All possible `ExtensionId[_ <: T]` for the different implementations of this extension.
    * They need to be able to be started on system start-up, to check if one exists in the system before starting the default.
    * The first one is considered as default, and will be started on use if no other is found running.
    * Implementing these as `case object` will simplify log reading.
    */
  override def possibilities: Seq[ExtensionId[_ <: MyExt] with ExtensionIdProvider] = Seq(MyExt1, MyExt2)
}

case object BadMyExtWithProvider extends MultiImplementedExtension[MyExt] with ExtensionIdProvider {
  /**
    * All possible `ExtensionId[_ <: T]` for the different implementations of this extension.
    * They need to be able to be started on system start-up, to check if one exists in the system before starting the default.
    * The first one is considered as default, and will be started on use if no other is found running.
    * Implementing these as `case object` will simplify log reading.
    */
  override def possibilities: Seq[ExtensionId[_ <: MyExt] with ExtensionIdProvider] = Seq(MyExt1, MyExt2)
}

case object BadMyExtEmpty extends MultiImplementedExtension[MyExt] {
  /**
    * All possible `ExtensionId[_ <: T]` for the different implementations of this extension.
    * They need to be able to be started on system start-up, to check if one exists in the system before starting the default.
    * The first one is considered as default, and will be started on use if no other is found running.
    * Implementing these as `case object` will simplify log reading.
    */
  override def possibilities: Seq[ExtensionId[_ <: MyExt] with ExtensionIdProvider] = Seq()
}