package com.ematiq.cs.demo.service

import com.ematiq.cs.demo.domain.TradeRequest
import com.ematiq.cs.demo.fx.ExchangeRateLookup
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import scala.concurrent.{ExecutionContext, Future}

final class ConversionServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  // Fixed "today" so that test dates in 2021 are always in the past
  private val fixedToday: () => LocalDate = () => LocalDate.parse("2025-01-15")

  "ConversionService" should {
    "convert non-EUR stake using provided rate and round to 2 decimals" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.8182677939441604")))
      val service = new ConversionService(lookup, fixedToday)

      // 2021-05-18 is a Tuesday — no business-day adjustment expected
      val request = TradeRequest(
        marketId = 123456L,
        selectionId = 987654L,
        odds = BigDecimal("2.2"),
        stake = BigDecimal("253.67"),
        currency = "USD",
        date = Instant.parse("2021-05-18T21:32:42.324Z")
      )

      val result = service.convert(request).futureValue

      result.currency shouldBe "EUR"
      result.stake shouldBe BigDecimal("207.57")
      lookup.lastFrom shouldBe Some("USD")
      lookup.lastTo shouldBe Some("EUR")
      lookup.lastDate shouldBe Some(LocalDate.parse("2021-05-18"))
    }

    "skip FX lookup for EUR source currency" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("999.0")))
      val service = new ConversionService(lookup, fixedToday)

      val request = TradeRequest(
        marketId = 1L,
        selectionId = 2L,
        odds = BigDecimal("1.5"),
        stake = BigDecimal("10.005"),
        currency = "eur",
        date = Instant.parse("2021-05-18T00:00:00Z")
      )

      val result = service.convert(request).futureValue

      result.currency shouldBe "EUR"
      result.stake shouldBe BigDecimal("10.01")
      lookup.calls shouldBe 0
    }

    "reject negative stake" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.8")))
      val service = new ConversionService(lookup, fixedToday)

      val request = TradeRequest(1L, 2L, BigDecimal("2.0"), BigDecimal("-100"), "USD", Instant.parse("2021-05-18T00:00:00Z"))

      val error = service.convert(request).failed.futureValue
      error shouldBe a[InvalidConversionRequest]
      error.getMessage should include("stake must be positive")
      lookup.calls shouldBe 0
    }

    "reject zero stake" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.8")))
      val service = new ConversionService(lookup, fixedToday)

      val request = TradeRequest(1L, 2L, BigDecimal("2.0"), BigDecimal("0"), "USD", Instant.parse("2021-05-18T00:00:00Z"))

      val error = service.convert(request).failed.futureValue
      error shouldBe a[InvalidConversionRequest]
      error.getMessage should include("stake must be positive")
    }

    "reject future date" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.8")))
      val today = () => LocalDate.parse("2021-05-18")
      val service = new ConversionService(lookup, today)

      val request = TradeRequest(1L, 2L, BigDecimal("2.0"), BigDecimal("100"), "USD", Instant.parse("2021-05-19T00:00:00Z"))

      val error = service.convert(request).failed.futureValue
      error shouldBe a[InvalidConversionRequest]
      error.getMessage should include("date must not be in the future")
      lookup.calls shouldBe 0
    }

    "normalize Saturday date to Friday for FX lookup" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.85")))
      val service = new ConversionService(lookup, fixedToday)

      // 2021-05-15 is a Saturday
      val request = TradeRequest(1L, 2L, BigDecimal("2.0"), BigDecimal("100"), "USD", Instant.parse("2021-05-15T12:00:00Z"))

      val result = service.convert(request).futureValue
      result.stake shouldBe BigDecimal("85.00")
      lookup.lastDate shouldBe Some(LocalDate.parse("2021-05-14")) // Friday
    }

    "normalize Sunday date to Friday for FX lookup" in {
      val lookup = new StubLookup(Future.successful(BigDecimal("0.85")))
      val service = new ConversionService(lookup, fixedToday)

      // 2021-05-16 is a Sunday
      val request = TradeRequest(1L, 2L, BigDecimal("2.0"), BigDecimal("100"), "USD", Instant.parse("2021-05-16T12:00:00Z"))

      val result = service.convert(request).futureValue
      result.stake shouldBe BigDecimal("85.00")
      lookup.lastDate shouldBe Some(LocalDate.parse("2021-05-14")) // Friday
    }
  }

  private final class StubLookup(result: Future[BigDecimal]) extends ExchangeRateLookup {
    var calls: Int = 0
    var lastFrom: Option[String] = None
    var lastTo: Option[String] = None
    var lastDate: Option[LocalDate] = None

    override def getRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
      calls += 1
      lastFrom = Some(from)
      lastTo = Some(to)
      lastDate = Some(date)
      result
    }
  }
}
