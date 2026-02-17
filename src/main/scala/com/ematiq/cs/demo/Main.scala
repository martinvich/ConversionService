package com.ematiq.cs.demo

import com.ematiq.cs.demo.api.TradeRoutes
import com.ematiq.cs.demo.config.AppConfig
import com.ematiq.cs.demo.fx.cache._
import com.ematiq.cs.demo.fx.provider._
import com.ematiq.cs.demo.service.ConversionService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.Http

import java.net.http.HttpClient
import scala.concurrent.ExecutionContext

object Main {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "conversion-service")
    implicit val ec: ExecutionContext = system.executionContext
    implicit val scheduler = system.scheduler

    val config = AppConfig.load(system.settings.config)
    val httpClient = HttpClient.newBuilder().build()

    val primaryProvider = new CnbExchangeRateProvider(config.cnb, httpClient)
    val fallbackProvider = new ExchangeRateHostProvider(config.exchangeRateHost, httpClient)
    val failoverProvider = new FailoverExchangeRateProvider(primaryProvider, fallbackProvider)

    val cacheActor = system.systemActorOf(
      FxRateCacheActor(config.cache.ttl, key => failoverProvider.fetchRate(key.from, key.to, key.date)),
      "fx-rate-cache"
    )

    val exchangeRateLookup = new CachedExchangeRateLookup(cacheActor, config.cache.askTimeout)
    val conversionService = new ConversionService(exchangeRateLookup)
    val routes = new TradeRoutes(conversionService).routes

    val bindingFuture =
      Http()(system.classicSystem).newServerAt(config.http.host, config.http.port).bind(routes)

    bindingFuture.foreach { binding =>
      system.log.info(
        s"Conversion service listening on http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}"
      )
    }

    bindingFuture.failed.foreach { error =>
      system.log.error(s"Failed to start HTTP server: ${error.getMessage}")
      system.terminate()
    }
  }
}
