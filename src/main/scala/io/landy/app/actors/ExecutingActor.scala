package io.landy.app.actors

import akka.actor.{Actor, ActorRef}
import akka.event.LoggingAdapter
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

trait DefaultTimeout {
  import scala.concurrent.duration._

  implicit val timeout: Timeout = 2.seconds
}

trait Logging {
  def log: LoggingAdapter
}

trait AskSupport extends akka.pattern.AskSupport {

  def ask[T](actor: ActorRef, message: Any)(implicit ec: ExecutionContext, timeout: Timeout): Future[T] =
    actor.ask(message).map { r => r.asInstanceOf[T] }

}

trait ExecutingActor extends Actor  with ActorTracing
                                    with AskSupport
                                    with DefaultTimeout {

  implicit val executionContext: ExecutionContext = context.dispatcher

}
