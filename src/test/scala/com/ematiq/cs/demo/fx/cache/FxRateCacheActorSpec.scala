package com.ematiq.cs.demo.fx.cache

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

final class FxRateCacheActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with Eventually {
  private val cacheKey = FxRateCacheActor.CacheKey("USD", "EUR", java.time.LocalDate.parse("2021-05-18"))

  "FxRateCacheActor" should {
    "serve cached value within TTL and refresh after expiry" in {
      var currentTime = Instant.parse("2021-05-18T00:00:00Z")
      var fetchCalls = 0

      val actor = spawn(
        FxRateCacheActor(
          ttl = 2.hours,
          fetch = _ => {
            fetchCalls += 1
            Future.successful(BigDecimal("0.8"))
          },
          now = () => currentTime
        )
      )

      val probe = TestProbe[FxRateCacheActor.LookupResponse]()

      actor ! FxRateCacheActor.GetRate(cacheKey, probe.ref)
      probe.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.8")))
      fetchCalls shouldBe 1

      actor ! FxRateCacheActor.GetRate(cacheKey, probe.ref)
      probe.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.8")))
      fetchCalls shouldBe 1

      currentTime = currentTime.plus(3, ChronoUnit.HOURS)
      actor ! FxRateCacheActor.GetRate(cacheKey, probe.ref)
      probe.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.8")))
      fetchCalls shouldBe 2
    }

    "deduplicate concurrent in-flight fetches" in {
      var fetchCalls = 0
      val promise = Promise[BigDecimal]()

      val actor = spawn(
        FxRateCacheActor(
          ttl = 2.hours,
          fetch = _ => {
            fetchCalls += 1
            promise.future
          }
        )
      )

      val probe1 = TestProbe[FxRateCacheActor.LookupResponse]()
      val probe2 = TestProbe[FxRateCacheActor.LookupResponse]()

      actor ! FxRateCacheActor.GetRate(cacheKey, probe1.ref)
      actor ! FxRateCacheActor.GetRate(cacheKey, probe2.ref)

      eventually {
        fetchCalls shouldBe 1
      }

      promise.success(BigDecimal("0.75"))

      probe1.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.75")))
      probe2.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.75")))
    }

    "keep fresh entries on cleanup tick" in {
      var currentTime = Instant.parse("2021-05-18T00:00:00Z")
      var fetchCalls = 0

      val actor = spawn(
        FxRateCacheActor(
          ttl = 2.hours,
          fetch = _ => {
            fetchCalls += 1
            Future.successful(BigDecimal("0.8"))
          },
          now = () => currentTime,
          cleanupInterval = 1.hour
        )
      )

      val probe = TestProbe[FxRateCacheActor.LookupResponse]()

      actor ! FxRateCacheActor.GetRate(cacheKey, probe.ref)
      probe.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.8")))
      fetchCalls shouldBe 1

      currentTime = currentTime.plus(1, ChronoUnit.HOURS)
      actor ! FxRateCacheActor.CleanupExpired

      actor ! FxRateCacheActor.GetRate(cacheKey, probe.ref)
      probe.expectMessage(FxRateCacheActor.LookupSuccess(BigDecimal("0.8")))
      fetchCalls shouldBe 1
    }
  }
}
