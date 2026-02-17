package com.ematiq.cs.demo.config

import com.typesafe.config.Config

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

final case class HttpConfig(host: String, port: Int)
final case class CacheConfig(ttl: FiniteDuration, askTimeout: FiniteDuration)
final case class CnbProviderConfig(baseUrl: String, timeout: FiniteDuration, lang: String)
final case class ExchangeRateHostConfig(baseUrl: String, timeout: FiniteDuration, accessKey: Option[String])

final case class AppConfig(
    http: HttpConfig,
    cache: CacheConfig,
    cnb: CnbProviderConfig,
    exchangeRateHost: ExchangeRateHostConfig
)

object AppConfig {
  def load(config: Config): AppConfig = {
    val appConfig = config.getConfig("app")

    val http = HttpConfig(
      host = appConfig.getString("http.host"),
      port = appConfig.getInt("http.port")
    )

    val cache = CacheConfig(
      ttl = FiniteDuration(appConfig.getDuration("cache.ttl").toMillis, MILLISECONDS),
      askTimeout = FiniteDuration(appConfig.getDuration("cache.ask-timeout").toMillis, MILLISECONDS)
    )

    val cnbConfig = appConfig.getConfig("providers.cnb")
    val cnb = CnbProviderConfig(
      baseUrl = cnbConfig.getString("base-url"),
      timeout = FiniteDuration(cnbConfig.getDuration("timeout").toMillis, MILLISECONDS),
      lang = cnbConfig.getString("lang")
    )

    val exchangerateHostConfig = appConfig.getConfig("providers.exchangerate-host")
    val rawAccessKey = exchangerateHostConfig.getString("access-key").trim
    val exchangeRateHost = ExchangeRateHostConfig(
      baseUrl = exchangerateHostConfig.getString("base-url"),
      timeout = FiniteDuration(exchangerateHostConfig.getDuration("timeout").toMillis, MILLISECONDS),
      accessKey = Option(rawAccessKey).filter(_.nonEmpty)
    )

    AppConfig(http, cache, cnb, exchangeRateHost)
  }
}
