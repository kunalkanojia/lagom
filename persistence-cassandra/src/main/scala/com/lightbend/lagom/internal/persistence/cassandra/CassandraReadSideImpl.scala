/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.lagom.internal.persistence.cassandra

import java.util
import java.util.concurrent.{ CompletableFuture, CompletionStage }
import java.util.function.{ BiFunction, Function, Supplier }

import akka.actor.ActorSystem
import com.google.inject.Injector
import com.lightbend.lagom.javadsl.persistence._
import javax.inject.Inject
import javax.inject.Singleton

import akka.Done
import akka.event.Logging
import com.datastax.driver.core.BoundStatement
import com.lightbend.lagom.internal.persistence.ReadSideImpl
import com.lightbend.lagom.javadsl.persistence.ReadSideProcessor.ReadSideHandler
import com.lightbend.lagom.javadsl.persistence.cassandra.{ CassandraReadSideProcessor, CassandraSession }
import com.lightbend.lagom.javadsl.persistence.cassandra.CassandraReadSide
import com.lightbend.lagom.javadsl.persistence.cassandra.CassandraReadSide.ReadSideHandlerBuilder
import org.pcollections.{ PSequence, TreePVector }

@Singleton
private[lagom] class CassandraReadSideImpl @Inject() (
  system: ActorSystem, session: CassandraSession, readSide: ReadSideImpl, injector: Injector
) extends CassandraReadSide {

  private val dispatcher = system.settings.config.getString("lagom.persistence.read-side.use-dispatcher")
  implicit val ec = system.dispatchers.lookup(dispatcher)

  override def register[Event <: AggregateEvent[Event]](
    processorClass: Class[_ <: CassandraReadSideProcessor[Event]]
  ): Unit = {

    readSide.registerFactory(
      () => {

        val processor = injector.getInstance(processorClass)

        new ReadSideProcessor[Event] {
          override def buildHandler(): ReadSideHandler[Event] = {
            new LegacyCassandraReadSideHandler[Event](session, processor, dispatcher)
          }

          override def aggregateTags(): PSequence[AggregateEventTag[Event]] = {
            TreePVector.singleton(processor.aggregateTag)
          }

          override def readSideName(): String = Logging.simpleName(processorClass)
        }
      }, processorClass
    )
  }

  override def builder[Event <: AggregateEvent[Event]](offsetTableName: String): ReadSideHandlerBuilder[Event] = {
    new ReadSideHandlerBuilder[Event] {
      import CassandraAutoReadSideHandler.Handler
      private var prepareCallback: AggregateEventTag[Event] => CompletionStage[Done] =
        tag => CompletableFuture.completedFuture(Done.getInstance())
      private var globalPrepareCallback: () => CompletionStage[Done] =
        () => CompletableFuture.completedFuture(Done.getInstance())
      private var handlers = Map.empty[Class[_ <: Event], Handler[Event]]

      override def setGlobalPrepare(callback: Supplier[CompletionStage[Done]]): ReadSideHandlerBuilder[Event] = {
        globalPrepareCallback = callback.get
        this
      }

      override def setPrepare(callback: Function[AggregateEventTag[Event], CompletionStage[Done]]): ReadSideHandlerBuilder[Event] = {
        prepareCallback = callback.apply
        this
      }

      override def setEventHandler[E <: Event](
        eventClass: Class[E],
        handler:    Function[E, CompletionStage[util.List[BoundStatement]]]
      ): ReadSideHandlerBuilder[Event] = {

        handlers += (eventClass -> ((event: E, offset: Offset) => handler(event)))
        this
      }

      override def setEventHandler[E <: Event](
        eventClass: Class[E],
        handler:    BiFunction[E, Offset, CompletionStage[util.List[BoundStatement]]]
      ): ReadSideHandlerBuilder[Event] = {

        handlers += (eventClass -> handler.apply _)
        this
      }

      override def build(): ReadSideHandler[Event] = {
        val offsetStore = new OffsetStore(session, offsetTableName)
        new CassandraAutoReadSideHandler[Event](session, handlers, globalPrepareCallback, prepareCallback, offsetStore, dispatcher)
      }
    }
  }
}
