package com.ematiq.cs.demo.api

import com.ematiq.cs.demo.domain.{TradeRequest, TradeResponse}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import java.time.Instant
import scala.util.Try

final case class ApiError(code: String, message: String)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object InstantJsonFormat extends JsonFormat[Instant] {
    override def write(obj: Instant): JsValue = JsString(obj.toString)

    override def read(json: JsValue): Instant = json match {
      case JsString(value) =>
        Try(Instant.parse(value)).getOrElse(deserializationError("date must be ISO-8601 instant"))
      case _ =>
        deserializationError("date must be string")
    }
  }

  implicit val tradeRequestFormat: RootJsonFormat[TradeRequest] = jsonFormat6(TradeRequest.apply)
  implicit val tradeResponseFormat: RootJsonFormat[TradeResponse] = jsonFormat6(TradeResponse.apply)
  implicit val apiErrorFormat: RootJsonFormat[ApiError] = jsonFormat2(ApiError.apply)
}
