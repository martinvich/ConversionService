package com.ematiq.cs.demo.fx.cache

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object FxRateCacheActor {
  final case class CacheKey(from: String, to: String, date: java.time.LocalDate)

  sealed trait LookupResponse
  final case class LookupSuccess(rate: BigDecimal) extends LookupResponse
  final case class LookupFailure(cause: Throwable) extends LookupResponse

  sealed trait Command
  final case class GetRate(key: CacheKey, replyTo: ActorRef[LookupResponse]) extends Command
  case object CleanupExpired extends Command
  private final case class WrappedFetchResult(key: CacheKey, result: Try[BigDecimal]) extends Command

  private final case class CachedRate(rate: BigDecimal, expiresAt: Instant)

  def apply(
      ttl: FiniteDuration,
      fetch: CacheKey => Future[BigDecimal],
      now: () => Instant = () => Instant.now(),
      cleanupInterval: FiniteDuration = 10.minutes
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(CleanupExpired, cleanupInterval)
      Behaviors.setup { context =>
        behavior(ttl, fetch, now, context, Map.empty, Map.empty)
      }
    }

  private def behavior(
      ttl: FiniteDuration,
      fetch: CacheKey => Future[BigDecimal],
      now: () => Instant,
      context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      cache: Map[CacheKey, CachedRate],
      inFlight: Map[CacheKey, Vector[ActorRef[LookupResponse]]]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetRate(key, replyTo) =>
        val currentTime = now()
        val freshCache = pruneExpired(cache, currentTime)
        freshCache.get(key) match {
          case Some(cachedRate) =>
            replyTo ! LookupSuccess(cachedRate.rate)
            behavior(ttl, fetch, now, context, freshCache, inFlight)
          case None =>
            inFlight.get(key) match {
              case Some(waiters) =>
                behavior(ttl, fetch, now, context, freshCache, inFlight.updated(key, waiters :+ replyTo))
              case None =>
                context.pipeToSelf(fetch(key)) {
                  case Success(rate) => WrappedFetchResult(key, Success(rate))
                  case Failure(err)  => WrappedFetchResult(key, Failure(err))
                }
                behavior(
                  ttl,
                  fetch,
                  now,
                  context,
                  freshCache,
                  inFlight.updated(key, Vector(replyTo))
                )
            }
        }

      case CleanupExpired =>
        behavior(ttl, fetch, now, context, pruneExpired(cache, now()), inFlight)

      case WrappedFetchResult(key, result) =>
        val waiters = inFlight.getOrElse(key, Vector.empty)
        result match {
          case Success(rate) =>
            waiters.foreach(_ ! LookupSuccess(rate))
            val expiresAt = now().plusMillis(ttl.toMillis)
            behavior(
              ttl,
              fetch,
              now,
              context,
              cache.updated(key, CachedRate(rate, expiresAt)),
              inFlight - key
            )
          case Failure(err) =>
            waiters.foreach(_ ! LookupFailure(err))
            behavior(ttl, fetch, now, context, cache - key, inFlight - key)
        }
    }

  private def pruneExpired(cache: Map[CacheKey, CachedRate], currentTime: Instant): Map[CacheKey, CachedRate] =
    cache.filter { case (_, value) => value.expiresAt.isAfter(currentTime) }
}
