package com.ematiq.cs.demo

import com.ematiq.cs.demo.api.{JsonSupport, TradeRoutes}
import com.ematiq.cs.demo.domain.TradeResponse
import com.ematiq.cs.demo.fx.ProviderUnavailable
import com.ematiq.cs.demo.fx.cache.{CachedExchangeRateLookup, FxRateCacheActor}
import com.ematiq.cs.demo.fx.provider.{ExchangeRateProvider, FailoverExchangeRateProvider}
import com.ematiq.cs.demo.service.ConversionService
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration._

final class TradeConversionIT extends AnyWordSpec with Matchers with ScalatestRouteTest with JsonSupport {

  private implicit val typedScheduler: org.apache.pekko.actor.typed.Scheduler = system.toTyped.scheduler
  private val fixedToday: () => LocalDate = () => LocalDate.parse("2025-01-15")
  private val actorCounter = new AtomicInteger(0)

  private def buildRoute(
      provider: ExchangeRateProvider,
      today: () => LocalDate = fixedToday
  ): Route = {
    val cacheActor = system.toTyped.systemActorOf(
      FxRateCacheActor(
        ttl = 1.hour,
        fetch = key => provider.fetchRate(key.from, key.to, key.date)
      ),
      s"it-fx-cache-${actorCounter.incrementAndGet()}"
    )
    val lookup = new CachedExchangeRateLookup(cacheActor, 5.seconds)(system.dispatcher, typedScheduler)
    val service = new ConversionService(lookup, today)(system.dispatcher)
    new TradeRoutes(service)(system.dispatcher).routes
  }

  private def tradeJson(currency: String, stake: BigDecimal, date: String): String =
    s"""{"marketId":1,"selectionId":2,"odds":1.5,"stake":$stake,"currency":"$currency","date":"$date"}"""

  private def postTrade(body: String) =
    Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body))

  "Trade conversion (full stack)" should {
    "convert USD stake to EUR using provider rate" in {
      val route = buildRoute(stubProvider(Future.successful(BigDecimal("0.82"))))
      val body = tradeJson("USD", BigDecimal("100.00"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[TradeResponse]
        response.currency shouldBe "EUR"
        response.stake shouldBe BigDecimal("82.00")
        response.marketId shouldBe 1
        response.selectionId shouldBe 2
        response.odds shouldBe BigDecimal("1.5")
      }
    }

    "pass through EUR trades without calling FX provider" in {
      val fetchCount = new AtomicInteger(0)
      val route = buildRoute(countingProvider(fetchCount, Future.successful(BigDecimal("0.82"))))
      val body = tradeJson("EUR", BigDecimal("50.00"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[TradeResponse]
        response.currency shouldBe "EUR"
        response.stake shouldBe BigDecimal("50.00")
      }

      fetchCount.get() shouldBe 0
    }

    "serve repeated identical requests from cache" in {
      val fetchCount = new AtomicInteger(0)
      val route = buildRoute(countingProvider(fetchCount, Future.successful(BigDecimal("0.82"))))
      val body = tradeJson("USD", BigDecimal("100.00"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[TradeResponse].stake shouldBe BigDecimal("82.00")
      }

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[TradeResponse].stake shouldBe BigDecimal("82.00")
      }

      fetchCount.get() shouldBe 1
    }

    "return 500 when provider is unavailable" in {
      val route = buildRoute(stubProvider(Future.failed(ProviderUnavailable("stub", "down"))))
      val body = tradeJson("USD", BigDecimal("100.00"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }

    "fall back to secondary provider when primary fails" in {
      val primary = stubProvider(Future.failed(ProviderUnavailable("primary", "down")))
      val fallback = stubProvider(Future.successful(BigDecimal("0.85")))
      val failover = new FailoverExchangeRateProvider(primary, fallback)(system.dispatcher)
      val route = buildRoute(failover)
      val body = tradeJson("USD", BigDecimal("100.00"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[TradeResponse].stake shouldBe BigDecimal("85.00")
      }
    }

    "adjust weekend dates to Friday business day" in {
      val fetchCount = new AtomicInteger(0)
      var fetchedDate: Option[LocalDate] = None
      val provider = new ExchangeRateProvider {
        val name = "date-tracking"
        def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
          fetchCount.incrementAndGet()
          fetchedDate = Some(date)
          Future.successful(BigDecimal("0.82"))
        }
      }
      val route = buildRoute(provider)
      // 2025-01-11 is a Saturday
      val body = tradeJson("USD", BigDecimal("100.00"), "2025-01-11T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[TradeResponse].stake shouldBe BigDecimal("82.00")
      }

      fetchCount.get() shouldBe 1
      fetchedDate shouldBe Some(LocalDate.parse("2025-01-10")) // Friday
    }

    "reject negative stake with 400" in {
      val route = buildRoute(stubProvider(Future.successful(BigDecimal("0.82"))))
      val body = tradeJson("USD", BigDecimal("-10"), "2025-01-14T10:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "reject future-dated trade with 400" in {
      val route = buildRoute(stubProvider(Future.successful(BigDecimal("0.82"))))
      val body = tradeJson("USD", BigDecimal("100.00"), "2099-01-01T00:00:00Z")

      postTrade(body) ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  private def stubProvider(result: Future[BigDecimal]): ExchangeRateProvider =
    new ExchangeRateProvider {
      val name = "stub"
      def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = result
    }

  private def countingProvider(counter: AtomicInteger, result: Future[BigDecimal]): ExchangeRateProvider =
    new ExchangeRateProvider {
      val name = "counting-stub"
      def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
        counter.incrementAndGet()
        result
      }
    }
}
