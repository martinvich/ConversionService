package com.ematiq.cs.demo.api

import com.ematiq.cs.demo.domain.TradeResponse
import com.ematiq.cs.demo.fx.{ExchangeRateLookup, ProviderUnavailable}
import com.ematiq.cs.demo.service.ConversionService
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import scala.concurrent.Future

final class TradeRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with JsonSupport {

  "TradeRoutes" should {
    "return converted trade payload in EUR" in {
      val route = buildRoute(new StubLookup(Future.successful(BigDecimal("0.8"))))
      val body =
        """
          |{
          |  "marketId": 123456,
          |  "selectionId": 987654,
          |  "odds": 2.2,
          |  "stake": 253.67,
          |  "currency": "USD",
          |  "date": "2021-05-18T21:32:42.324Z"
          |}
          |""".stripMargin

      Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body)) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[TradeResponse]
        response.currency shouldBe "EUR"
        response.stake shouldBe BigDecimal("202.94")
      }
    }

    "return 500 when conversion providers fail" in {
      val route = buildRoute(new StubLookup(Future.failed(ProviderUnavailable("test", "down"))))
      val body =
        """
          |{
          |  "marketId": 123456,
          |  "selectionId": 987654,
          |  "odds": 2.2,
          |  "stake": 253.67,
          |  "currency": "USD",
          |  "date": "2021-05-18T21:32:42.324Z"
          |}
          |""".stripMargin

      Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body)) ~> route ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }

    "return 400 for malformed payload" in {
      val route = buildRoute(new StubLookup(Future.successful(BigDecimal("0.8"))))
      val body = """{"marketId":"not-a-number"}"""

      Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body)) ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "return 400 for negative stake" in {
      val route = buildRoute(new StubLookup(Future.successful(BigDecimal("0.8"))))
      val body =
        """
          |{
          |  "marketId": 123456,
          |  "selectionId": 987654,
          |  "odds": 2.2,
          |  "stake": -10,
          |  "currency": "USD",
          |  "date": "2021-05-18T21:32:42.324Z"
          |}
          |""".stripMargin

      Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body)) ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "return 400 for future date" in {
      val route = buildRoute(new StubLookup(Future.successful(BigDecimal("0.8"))))
      val body =
        """
          |{
          |  "marketId": 123456,
          |  "selectionId": 987654,
          |  "odds": 2.2,
          |  "stake": 100,
          |  "currency": "USD",
          |  "date": "2099-01-01T00:00:00Z"
          |}
          |""".stripMargin

      Post("/api/v1/conversion/trade", HttpEntity(ContentTypes.`application/json`, body)) ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  private def buildRoute(lookup: ExchangeRateLookup) = {
    val service = new ConversionService(lookup)(system.dispatcher)
    new TradeRoutes(service)(system.dispatcher).routes
  }

  private final class StubLookup(result: Future[BigDecimal]) extends ExchangeRateLookup {
    override def getRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = result
  }
}
