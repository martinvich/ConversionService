package com.ematiq.cs.demo.api

import com.ematiq.cs.demo.domain.{TradeRequest, TradeResponse}
import com.ematiq.cs.demo.fx.ExchangeRateException
import com.ematiq.cs.demo.service.{ConversionService, InvalidConversionRequest}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, MalformedRequestContentRejection, RejectionHandler, Route}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class TradeRoutes(conversionService: ConversionService)(implicit ec: ExecutionContext) extends JsonSupport {

  private val rejectionHandler = RejectionHandler.newBuilder()
    .handle {
      case MalformedRequestContentRejection(message, _) =>
        complete(StatusCodes.BadRequest -> ApiError("MALFORMED_BODY", message))
    }
    .result()

  private val exceptionHandler = ExceptionHandler {
    case _: InvalidConversionRequest =>
      complete(StatusCodes.BadRequest -> ApiError("INVALID_REQUEST", "Invalid trade request"))
    case _: ExchangeRateException =>
      complete(StatusCodes.InternalServerError -> ApiError("RATE_UNAVAILABLE", "Unable to convert stake to EUR"))
    case error: Throwable =>
      extractLog { log =>
        log.error(error, "Unhandled route error")
        complete(StatusCodes.InternalServerError -> ApiError("INTERNAL_ERROR", "Internal server error"))
      }
  }

  val routes: Route =
    handleExceptions(exceptionHandler) {
      handleRejections(rejectionHandler) {
        path("api" / "v1" / "conversion" / "trade") {
          post {
            entity(as[TradeRequest]) { tradeRequest =>
              onComplete(conversionService.convert(tradeRequest).map(TradeResponse.from)) {
                case Success(response) => complete(StatusCodes.OK -> response)
                case Failure(error)    => throw error
              }
            }
          }
        }
      }
    }
}
