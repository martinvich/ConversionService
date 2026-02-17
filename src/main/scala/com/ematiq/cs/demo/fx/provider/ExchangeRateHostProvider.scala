package com.ematiq.cs.demo.fx.provider

import com.ematiq.cs.demo.config.ExchangeRateHostConfig
import com.ematiq.cs.demo.fx._
import spray.json.DefaultJsonProtocol._
import spray.json.{DeserializationException, JsObject}
import spray.json._

import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Try

final class ExchangeRateHostProvider(
    config: ExchangeRateHostConfig,
    httpClient: HttpClient
)(implicit ec: ExecutionContext)
    extends ExchangeRateProvider {

  override val name: String = "exchangerate-host"

  override def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
    if (from == to) {
      Future.successful(BigDecimal(1))
    } else {
      val baseParams = Seq(
        "base" -> from,
        "symbols" -> to
      )
      val fullParams = config.accessKey match {
        case Some(key) => baseParams :+ ("access_key" -> key)
        case None      => baseParams
      }
      val query = fullParams.map { case (k, v) => s"${UrlEncoding.encode(k)}=${UrlEncoding.encode(v)}" }.mkString("&")
      val uri = URI.create(s"${config.baseUrl}/${date.toString}?$query")
      val request =
        HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(config.timeout.toMillis)).GET().build()

      httpClient
        .sendAsync(request, BodyHandlers.ofString())
        .asScala
        .flatMap { response =>
          if (response.statusCode() / 100 != 2) {
            Future.failed(ProviderUnavailable(name, s"HTTP ${response.statusCode()}"))
          } else {
            parseRate(response.body(), from, to, date)
          }
        }
        .recoverWith {
          case e: ExchangeRateException => Future.failed(e)
          case e                        => Future.failed(ProviderUnavailable(name, e.getMessage, e))
        }
    }
  }

  private def parseRate(payload: String, from: String, to: String, requestedDate: LocalDate): Future[BigDecimal] =
    Try(payload.parseJson.asJsObject).toEither match {
      case Left(e: DeserializationException) =>
        Future.failed(InvalidProviderResponse(name, e.getMessage))
      case Left(e) =>
        Future.failed(ProviderUnavailable(name, e.getMessage, e))
      case Right(json) =>
        val success = json.fields.get("success") match {
          case Some(value) => Try(value.convertTo[Boolean]).getOrElse(false)
          case None        => true
        }

        if (!success) {
          val errorMessage = extractErrorMessage(json).getOrElse("unknown API error")
          Future.failed(ProviderUnavailable(name, errorMessage))
        } else {
          json.fields.get("rates") match {
            case Some(ratesObj: JsObject) =>
              ratesObj.fields.get(to) match {
                case Some(value) =>
                  Try(value.convertTo[BigDecimal]).toEither match {
                    case Right(rate) =>
                      val normalized = BigDecimal(rate.bigDecimal.setScale(16, RoundingMode.HALF_UP))
                      Future.successful(normalized)
                    case Left(_) =>
                      Future.failed(
                        InvalidProviderResponse(name, s"Rate for $to is not a valid decimal")
                      )
                  }
                case None =>
                  Future.failed(RateNotFound(name, from, to, requestedDate))
              }
            case _ =>
              Future.failed(InvalidProviderResponse(name, "Missing rates object"))
          }
        }
    }

  private def extractErrorMessage(json: JsObject): Option[String] =
    json.fields.get("error") match {
      case Some(errorObj: JsObject) =>
        errorObj.fields.get("info").flatMap { info =>
          Try(info.convertTo[String]).toOption
        }.orElse {
          errorObj.fields.get("type").flatMap { t =>
            Try(t.convertTo[String]).toOption
          }
        }
      case _ => None
    }
}
