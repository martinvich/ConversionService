package com.ematiq.cs.demo.fx.provider

import com.ematiq.cs.demo.fx.ProviderUnavailable

import java.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}

final class FailoverExchangeRateProvider(
    primary: ExchangeRateProvider,
    fallback: ExchangeRateProvider
)(implicit ec: ExecutionContext)
    extends ExchangeRateProvider {

  override val name: String = s"${primary.name}->${fallback.name}"

  override def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] =
    primary
      .fetchRate(from, to, date)
      .recoverWith { case primaryError: ProviderUnavailable =>
        fallback
          .fetchRate(from, to, date)
          .recoverWith { case fallbackError: ProviderUnavailable =>
            Future.failed(
              ProviderUnavailable(
                name,
                s"primary failed (${primaryError.getMessage}); fallback failed (${fallbackError.getMessage})",
                fallbackError
              )
            )
          }
      }
}
