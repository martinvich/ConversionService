package com.ematiq.cs.demo.fx.provider

import java.time.LocalDate

import scala.concurrent.Future

trait ExchangeRateProvider {
  def name: String

  def fetchRate(from: String, to: String, date: LocalDate): Future[BigDecimal]
}
