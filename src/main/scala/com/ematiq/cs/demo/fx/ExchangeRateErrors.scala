package com.ematiq.cs.demo.fx

import java.time.LocalDate

sealed abstract class ExchangeRateException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

final case class ProviderUnavailable(providerName: String, details: String, cause0: Throwable = null)
    extends ExchangeRateException(s"$providerName unavailable: $details", cause0)

final case class RateNotFound(providerName: String, from: String, to: String, date: LocalDate)
    extends ExchangeRateException(s"$providerName has no rate for $from->$to on $date")

final case class InvalidProviderResponse(providerName: String, details: String)
    extends ExchangeRateException(s"$providerName returned invalid response: $details")
