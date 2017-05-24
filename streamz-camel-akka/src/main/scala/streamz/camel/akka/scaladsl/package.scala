/*
 * Copyright 2014 - 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streamz.camel.akka

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._

import org.apache.camel.spi.Synchronization
import org.apache.camel.{ Exchange, ExchangePattern, TypeConversionException }

import streamz.camel.{ StreamContext, StreamMessage }

import scala.concurrent.{ Future, Promise }
import scala.reflect.ClassTag
import scala.util._

package object scaladsl {
  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `FlowOps[StreamMessage[A], M]`.
   */
  class StreamMessageDsl[A, M, FO <: FlowOps[StreamMessage[A], M]](val self: FO) {
    /**
     * @see [[scaladsl.send]]
     */
    def send(uri: String, parallelism: Int = 1)(implicit context: StreamContext): self.Repr[StreamMessage[A]] =
      self.via(scaladsl.send[A](uri, parallelism))

    /**
     * @see [[scaladsl.sendRequest]]
     */
    def sendRequest[B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): self.Repr[StreamMessage[B]] =
      self.via(scaladsl.sendRequest[A, B](uri, parallelism))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `FlowOps[A, M]`.
   */
  class StreamMessageBodyDsl[A, M, FO <: FlowOps[A, M]](val self: FO) {
    /**
     * @see [[scaladsl.sendBody]]
     */
    def send(uri: String, parallelism: Int = 1)(implicit context: StreamContext): self.Repr[A] =
      self.via(scaladsl.sendBody[A](uri, parallelism))

    /**
     * @see [[scaladsl.sendRequestBody]]
     */
    def sendRequest[B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): self.Repr[B] =
      self.via(sendRequestBody[A, B](uri, parallelism))
  }

  class StreamMessageFlowDsl[A, M](val self: Flow[StreamMessage[A], StreamMessage[A], M]) {
    /**
     * Pipes the flow's output to its input. Terminal operation on a flow created with [[receiveRequest]]
     * whose output type has been transformed to its input type.
     *
     * @see [[receiveRequest]]
     */
    def reply: RunnableGraph[M] = self.joinMat(Flow[StreamMessage[A]])(Keep.left)
  }

  class StreamMessageBodyFlowDsl[A, M](val self: Flow[A, A, M]) {
    /**
     * Pipes the flow's output to its input. Terminal operation on a flow created with [[receiveRequestBody]]
     * whose output type has been transformed to its input type.
     *
     * @see [[receiveRequestBody]]
     */
    def reply: RunnableGraph[M] = self.joinMat(Flow[A])(Keep.left)
  }

  implicit def streamMessageSourceDsl[A, M](self: Source[StreamMessage[A], M]): StreamMessageDsl[A, M, Source[StreamMessage[A], M]] =
    new StreamMessageDsl(self)

  implicit def streamMessageFlowDsl[A, B, M](self: Flow[A, StreamMessage[B], M]): StreamMessageDsl[B, M, Flow[A, StreamMessage[B], M]] =
    new StreamMessageDsl(self)

  implicit def streamMessageFlowDsl[A, M](self: Flow[StreamMessage[A], StreamMessage[A], M]): StreamMessageFlowDsl[A, M] =
    new StreamMessageFlowDsl(self)

  implicit def streamMessageSubFlowOfSourceDsl[A, M](self: SubFlow[StreamMessage[A], M, Source[StreamMessage[A], M]#Repr, Source[StreamMessage[A], M]#Closed]): StreamMessageDsl[A, M, SubFlow[StreamMessage[A], M, Source[StreamMessage[A], M]#Repr, Source[StreamMessage[A], M]#Closed]] =
    new StreamMessageDsl(self)

  implicit def streamMessageSubFlowOfFlowDsl[A, B, M](self: SubFlow[StreamMessage[B], M, Flow[A, StreamMessage[B], M]#Repr, Flow[A, StreamMessage[B], M]#Closed]): StreamMessageDsl[B, M, SubFlow[StreamMessage[B], M, Flow[A, StreamMessage[B], M]#Repr, Flow[A, StreamMessage[B], M]#Closed]] =
    new StreamMessageDsl(self)

  implicit def streamMessageBodySourceDsl[A, M](self: Source[A, M]): StreamMessageBodyDsl[A, M, Source[A, M]] =
    new StreamMessageBodyDsl(self)

  implicit def streamMessageBodyFlowDsl[A, B, M](self: Flow[A, B, M]): StreamMessageBodyDsl[B, M, Flow[A, B, M]] =
    new StreamMessageBodyDsl(self)

  implicit def streamMessageFlowDsl[A, M](self: Flow[A, A, M]): StreamMessageBodyFlowDsl[A, M] =
    new StreamMessageBodyFlowDsl(self)

  implicit def streamMessageBodySubFlowOfSourceDsl[A, M](self: SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]): StreamMessageBodyDsl[A, M, SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]] =
    new StreamMessageBodyDsl(self)

  implicit def streamMessageBodySubFlowOfFlowDsl[A, B, M](self: SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]): StreamMessageBodyDsl[B, M, SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]] =
    new StreamMessageBodyDsl(self)

  /**
   * Creates a source of [[StreamMessage]]s consumed from the Camel endpoint identified by `uri`.
   * [[StreamMessage]] bodies are converted to type `A` using a Camel type converter. The source
   * completes with an error if the message exchange with the endpoint fails.
   *
   * Use this method for processing inbound [[ExchangePattern.InOnly]] message exchanges.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def receive[A](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[A]): Source[StreamMessage[A], NotUsed] =
    consumeInOnly[A](uri)

  /**
   * Creates a flow of [[StreamMessage]]s whose output is consumed as request messages from the Camel endpoint identified by
   * `uri` and whose input is sent as reply messages to that endpoint. Reply messages are expected to be in the
   * same order as their corresponding request messages. Request [[StreamMessage]] bodies are converted
   * to type `B` using a Camel type converter. The flow completes with an error if the message exchange
   * with the endpoint fails.
   *
   * Use this method for processing inbound [[ExchangePattern.InOut]] message exchanges.
   *
   * @param uri Camel endpoint URI.
   * @param capacity maximum number of active requests i.e. maximum number of consumed messages with
   *                 pending reply messages.
   * @throws TypeConversionException if type conversion fails.
   */
  def receiveRequest[A, B](uri: String, capacity: Int = 16)(implicit streamContext: StreamContext, tag: ClassTag[B]): Flow[StreamMessage[A], StreamMessage[B], NotUsed] =
    consumeInOut[A, B](uri, capacity)

  /**
   * Creates a source of messages consumed from the Camel endpoint identified by `uri`.
   * Messages are converted to type `A` using a Camel type converter. The source
   * completes with an error if the message exchange with the endpoint fails.
   *
   * Use this method for processing inbound [[ExchangePattern.InOnly]] message exchanges.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def receiveBody[A](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[A]): Source[A, NotUsed] =
    consumeInOnly[A](uri).map(_.body)

  /**
   * Creates a flow of messages whose output is consumed as request messages from the Camel endpoint identified by
   * `uri` and whose input is sent as reply messages to that endpoint. Reply messages are expected to be in the
   * same order as their corresponding request messages. Request message bodies are converted
   * to type `B` using a Camel type converter. The flow completes with an error if the message exchange
   * with the endpoint fails.
   *
   * Use this method for processing inbound [[ExchangePattern.InOut]] message exchanges.
   *
   * @param uri Camel endpoint URI.
   * @param capacity maximum number of active requests i.e. maximum number of consumed messages with
   *                 pending reply messages.
   * @throws TypeConversionException if type conversion fails.
   */
  def receiveRequestBody[A, B](uri: String, capacity: Int = 16)(implicit streamContext: StreamContext, tag: ClassTag[B]): Flow[A, B, NotUsed] =
    Flow[A].map(StreamMessage(_)).via(consumeInOut[A, B](uri, capacity)).map(_.body)

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOnly]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the input [[StreamMessage]] after the endpoint has processed
   * that message. The flow completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @param parallelism number of parallel sends. Message order preserved for any `parallelism` value.
   */
  def send[A](uri: String, parallelism: Int = 1)(implicit context: StreamContext): Graph[FlowShape[StreamMessage[A], StreamMessage[A]], NotUsed] =
    Flow[StreamMessage[A]].mapAsync(parallelism)(produce[A, A](uri, _, ExchangePattern.InOnly, (message, _) => message))

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOnly]] message exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the input message after the endpoint has processed
   * that message. The flow completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @param parallelism number of parallel sends. Message order preserved for any `parallelism` value.
   */
  def sendBody[A](uri: String, parallelism: Int = 1)(implicit context: StreamContext): Graph[FlowShape[A, A], NotUsed] =
    Flow[A].map(StreamMessage(_)).via(send[A](uri, parallelism)).map(_.body)

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOut]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the output [[StreamMessage]] received from the endpoint. The
   * output [[StreamMessage]] body is converted to type `B` using a Camel type converter. The flow completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @param parallelism number of parallel requests.  Message order preserved for any `parallelism` value.
   * @throws TypeConversionException if type conversion fails.
   */
  def sendRequest[A, B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): Graph[FlowShape[StreamMessage[A], StreamMessage[B]], NotUsed] =
    Flow[StreamMessage[A]].mapAsync(parallelism)(produce[A, B](uri, _, ExchangePattern.InOut, (_, exchange) => StreamMessage.from[B](exchange.getOut)))

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOut]] message exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the output message received from the endpoint. The
   * output message is converted to type `B` using a Camel type converter. The flow completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @param parallelism number of parallel requests.  Message order preserved for any `parallelism` value.
   * @throws TypeConversionException if type conversion fails.
   */
  def sendRequestBody[A, B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): Graph[FlowShape[A, B], NotUsed] =
    Flow[A].map(StreamMessage(_)).via(sendRequest[A, B](uri, parallelism)).map(_.body)

  private def consumeInOnly[A](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[A]): Source[StreamMessage[A], NotUsed] =
    Source.actorPublisher[StreamMessage[A]](EndpointConsumer.props[A](uri)).mapMaterializedValue(_ => NotUsed)

  private def consumeInOut[A, B](uri: String, capacity: Int)(implicit streamContext: StreamContext, tag: ClassTag[B]): Flow[StreamMessage[A], StreamMessage[B], NotUsed] =
    Flow.fromGraph(new EndpointConsumerReplier[A, B](uri, capacity))

  private def produce[A, B](uri: String, message: StreamMessage[A], pattern: ExchangePattern, result: (StreamMessage[A], Exchange) => StreamMessage[B])(implicit context: StreamContext): Future[StreamMessage[B]] = {
    val promise = Promise[StreamMessage[B]]()
    context.producerTemplate.asyncCallback(uri, context.createExchange(message, pattern), new Synchronization {
      override def onFailure(exchange: Exchange): Unit =
        promise.failure(exchange.getException)
      override def onComplete(exchange: Exchange): Unit = Try(result(message, exchange)) match {
        case Success(r) => promise.success(result(message, exchange))
        case Failure(e) => promise.failure(e)
      }
    })
    promise.future
  }
}
