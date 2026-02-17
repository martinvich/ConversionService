package com.ematiq.cs.demo.fx.cache

import com.ematiq.cs.demo.fx.ExchangeRateLookup
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

final class CachedExchangeRateLookup(
    cacheActor: ActorRef[FxRateCacheActor.Command],
    askTimeout: FiniteDuration
)(implicit ec: ExecutionContext, scheduler: org.apache.pekko.actor.typed.Scheduler)
    extends ExchangeRateLookup {

  private implicit val timeout: Timeout = Timeout(askTimeout)

  override def getRate(from: String, to: String, date: LocalDate): Future[BigDecimal] = {
    val key = FxRateCacheActor.CacheKey(from, to, date)

    cacheActor
      .ask(replyTo => FxRateCacheActor.GetRate(key, replyTo))
      .flatMap {
        case FxRateCacheActor.LookupSuccess(rate) => Future.successful(rate)
        case FxRateCacheActor.LookupFailure(err)  => Future.failed(err)
      }
  }
}
