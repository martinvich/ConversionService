package com.ematiq.cs.demo.fx.provider

import com.ematiq.cs.demo.fx._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

final class FailoverExchangeRateProviderSpec extends AnyWordSpec with Matchers with ScalaFutures {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val date = LocalDate.parse("2021-05-18")

  "FailoverExchangeRateProvider" should {
    "use primary provider when primary succeeds" in {
      val primary = new StubProvider("primary", Future.successful(BigDecimal("0.8")))
      val fallback = new StubProvider("fallback", Future.successful(BigDecimal("0.9")))
      val provider = new FailoverExchangeRateProvider(primary, fallback)

      provider.fetchRate("USD", "EUR", date).futureValue shouldBe BigDecimal("0.8")
      primary.calls shouldBe 1
      fallback.calls shouldBe 0
    }

    "use fallback provider when primary is unavailable" in {
      val primary = new StubProvider("primary", Future.failed(ProviderUnavailable("primary", "down")))
      val fallback = new StubProvider("fallback", Future.successful(BigDecimal("0.9")))
      val provider = new FailoverExchangeRateProvider(primary, fallback)

      provider.fetchRate("USD", "EUR", date).futureValue shouldBe BigDecimal("0.9")
      primary.calls shouldBe 1
      fallback.calls shouldBe 1
    }

    "fail when both providers are unavailable" in {
      val primary = new StubProvider("primary", Future.failed(ProviderUnavailable("primary", "down")))
      val fallback = new StubProvider("fallback", Future.failed(ProviderUnavailable("fallback", "down")))
      val provider = new FailoverExchangeRateProvider(primary, fallback)

      val error = provider.fetchRate("USD", "EUR", date).failed.futureValue
      error shouldBe a[ProviderUnavailable]
      primary.calls shouldBe 1
      fallback.calls shouldBe 1
    }

    "not call fallback when primary returns rate not found" in {
      val primary = new StubProvider(
        "primary",
        Future.failed(RateNotFound("primary", "USD", "EUR", date))
      )
      val fallback = new StubProvider("fallback", Future.successful(BigDecimal("0.9")))
      val provider = new FailoverExchangeRateProvider(primary, fallback)

      val error = provider.fetchRate("USD", "EUR", date).failed.futureValue
      error shouldBe a[RateNotFound]
      primary.calls shouldBe 1
      fallback.calls shouldBe 0
    }

    "not call fallback when primary returns invalid response" in {
      val primary = new StubProvider(
        "primary",
        Future.failed(InvalidProviderResponse("primary", "bad payload"))
      )
      val fallback = new StubProvider("fallback", Future.successful(BigDecimal("0.9")))
      val provider = new FailoverExchangeRateProvider(primary, fallback)

      val error = provider.fetchRate("USD", "EUR", date).failed.futureValue
      error shouldBe a[InvalidProviderResponse]
      primary.calls shouldBe 1
      fallback.calls shouldBe 0
    }
  }

  private final class StubProvider(val name: String, result: Future[BigDecimal]) extends ExchangeRateProvider {
    var calls: Int = 0

    override def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
      calls += 1
      result
    }
  }
}
