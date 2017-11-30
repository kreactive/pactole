package com.kreactive.http

import akka.Done
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshal}
import akka.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by cyrille on 07/02/2017.
  */
trait HttpUtils {
  implicit class HttpResponseOps(resp: HttpResponse) {
    def valid(validStatuses: Set[StatusCode] = Set(StatusCodes.OK))(implicit mat: Materializer, executor: ExecutionContext) =
      if (validStatuses.contains(resp.status)) Future.successful(resp)
      else drain.map(_ => throw new IllegalStateException(s"Invalid response status code: ${resp.status}"))

    def drain(implicit mat: Materializer, executor: ExecutionContext) = HttpUtils.drain(resp)

    def unmarshalOrDrain[T: FromEntityUnmarshaller](implicit mat: Materializer, executor: ExecutionContext) =
      Unmarshal(resp.entity).to[T].recoverWith{
        case e => drain.map(_ => throw e)
      }
  }
}

object HttpUtils extends HttpUtils {
  def drain(httpResponse: HttpResponse)(implicit mat: Materializer, executor: ExecutionContext): Future[Done] = {
    if(httpResponse.entity.isKnownEmpty())
      Future.successful(Done)
    else
      httpResponse.discardEntityBytes().future()
  }
}
