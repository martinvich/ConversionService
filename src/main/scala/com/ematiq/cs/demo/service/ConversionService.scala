package com.ematiq.cs.demo.service

import com.ematiq.cs.demo.domain.CurrencyConvertible
import com.ematiq.cs.demo.fx.ExchangeRateLookup
import org.slf4j.LoggerFactory

import java.math.RoundingMode
import java.time.{DayOfWeek, LocalDate, ZoneOffset}
import java.util.Locale
import scala.concurrent.{ExecutionContext, Future}

final case class InvalidConversionRequest(message: String) extends RuntimeException(message)

final class ConversionService(
    exchangeRateLookup: ExchangeRateLookup,
    today: () => LocalDate = () => LocalDate.now(ZoneOffset.UTC)
)(implicit ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)
  private val TargetCurrency = "EUR"

  def convert[T: CurrencyConvertible](request: T): Future[T] = {
    val cc = implicitly[CurrencyConvertible[T]]
    val sourceCurrency = normalizeCurrency(cc.getCurrency(request))
    val stake = cc.getStake(request)
    val date = cc.getDate(request)

    if (sourceCurrency.isEmpty) {
      Future.failed(InvalidConversionRequest("currency must not be empty"))
    } else if (stake <= 0) {
      Future.failed(InvalidConversionRequest("stake must be positive"))
    } else {
      val requestDayUtc = date.atZone(ZoneOffset.UTC).toLocalDate
      if (requestDayUtc.isAfter(today())) {
        Future.failed(InvalidConversionRequest("date must not be in the future"))
      } else {
        val effectiveDate = toBusinessDay(requestDayUtc)
        if (effectiveDate != requestDayUtc) {
          log.debug("Requested rate for {}, using effective business day {}", requestDayUtc, effectiveDate)
        }

        val rateF =
          if (sourceCurrency == TargetCurrency) Future.successful(BigDecimal(1))
          else exchangeRateLookup.getRate(sourceCurrency, TargetCurrency, effectiveDate)

        rateF.map { rate =>
          val convertedStake = BigDecimal((stake * rate).bigDecimal.setScale(2, RoundingMode.HALF_UP))
          cc.withConvertedStake(request, convertedStake, TargetCurrency)
        }
      }
    }
  }

  // FX spot markets operate Sun evening-Fri evening. Central banks publish fixings only on
  // business days. The standard convention is that Friday's rate remains valid over the weekend.
  private def toBusinessDay(date: LocalDate): LocalDate = date.getDayOfWeek match {
    case DayOfWeek.SATURDAY => date.minusDays(1)
    case DayOfWeek.SUNDAY   => date.minusDays(2)
    case _                  => date
  }

  private def normalizeCurrency(rawCurrency: String): String =
    rawCurrency.trim.toUpperCase(Locale.ROOT)
}
