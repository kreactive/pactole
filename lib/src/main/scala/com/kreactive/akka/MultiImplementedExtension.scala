package com.kreactive.akka

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}

/**
  * A trait to deal with several implementations for the same akka extension.
  * Such an extension should NOT be started on system start-up, so you should not implement `ExtensionIdProvider`.
  * This trait should be extended in a case object, for ease of log reading.
  */
trait MultiImplementedExtension[T <: Extension] extends ExtensionId[T] {
  /**
    * All possible `ExtensionId[_ <: T]` for the different implementations of this extension.
    * They need to be able to be started on system start-up, to check if one exists in the system before starting the default.
    * The first one is considered as default, and will be started on use if no other is found running.
    * Implementing these as `case object` will simplify log reading.
    */
  def possibilities: Seq[ExtensionId[_ <: T] with ExtensionIdProvider]

  def failIfNotFound: Boolean = false

  def createExtension(system: ExtendedActorSystem): T = {
    possibilities.foldLeft[Option[T]](None){ (o, extId) =>
      o.orElse(if (system.hasExtension(extId)) Some(system.extension(extId.asInstanceOf[ExtensionId[T]])) else None)
    }.orElse{
      possibilities.headOption.filterNot(_ => failIfNotFound).map{ extId =>
        system.log.warning(s"No started implementation found for extension $this. Starting default implementation $extId")
        extId.createExtension(system)
      }
    }.getOrElse{
      val errorMsg = s"No implementation found for extension $this. Implement some or populate the sequence of all possibilities"
      system.log.error(errorMsg)
      throw new NoSuchElementException(errorMsg)
    }
  }

  final def lookup(): ExtensionId[T] = throw new NoSuchElementException("You should not use this extension as a start-up service")
}