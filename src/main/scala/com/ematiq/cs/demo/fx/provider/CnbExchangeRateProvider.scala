package com.ematiq.cs.demo.fx.provider

import com.ematiq.cs.demo.config.CnbProviderConfig
import com.ematiq.cs.demo.fx._
import com.ematiq.cs.demo.fx.provider.CnbApiJson._
import spray.json.DeserializationException
import spray.json._

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import java.math.RoundingMode
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Try

final class CnbExchangeRateProvider(
    config: CnbProviderConfig,
    httpClient: HttpClient
)(implicit ec: ExecutionContext)
    extends ExchangeRateProvider {

  override val name: String = "cnb"

  override def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
    if (from == to) {
      Future.successful(BigDecimal(1))
    } else {
      val query = s"date=${UrlEncoding.encode(date.toString)}&lang=${UrlEncoding.encode(config.lang)}"
      val uri = URI.create(s"${config.baseUrl}/exrates/daily?$query")
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

  private def parseRate(
      payload: String,
      from: String,
      to: String,
      requestedDate: LocalDate
  ): Future[BigDecimal] =
    Try(payload.parseJson.convertTo[CnbDailyResponse]).toEither match {
      case Left(e: DeserializationException) =>
        Future.failed(InvalidProviderResponse(name, e.getMessage))
      case Left(e) =>
        Future.failed(ProviderUnavailable(name, e.getMessage, e))
      case Right(response) =>
        toCrossRate(response, from, to, requestedDate)
    }

  private def toCrossRate(
      response: CnbDailyResponse,
      from: String,
      to: String,
      requestedDate: LocalDate
  ): Future[BigDecimal] = {
    if (response.rates.isEmpty) {
      Future.failed(RateNotFound(name, from, to, requestedDate))
    } else {
      val czkPerUnitByCurrency = response.rates.map { row =>
        row.currencyCode.toUpperCase(Locale.ROOT) -> (row.rate / row.amount)
      }.toMap + ("CZK" -> BigDecimal(1))

      val maybeFrom = czkPerUnitByCurrency.get(from)
      val maybeTo = czkPerUnitByCurrency.get(to)

      // CNB only publishes CZK-based rates, so we compute a cross rate: (FROM/CZK) / (TO/CZK).
      // This introduces a rounding step that wouldn't exist with a direct quote.
      (maybeFrom, maybeTo) match {
        case (Some(fromToCzk), Some(toToCzk)) =>
          val cross = BigDecimal(fromToCzk.bigDecimal.divide(toToCzk.bigDecimal, 16, RoundingMode.HALF_UP))
          Future.successful(cross)
        case _ =>
          Future.failed(RateNotFound(name, from, to, requestedDate))
      }
    }
  }
}
