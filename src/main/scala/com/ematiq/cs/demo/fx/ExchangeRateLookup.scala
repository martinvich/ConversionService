package com.ematiq.cs.demo.fx

import java.time.LocalDate

import scala.concurrent.Future

trait ExchangeRateLookup {
  def getRate(from: String, to: String, date: LocalDate): Future[BigDecimal]
}
