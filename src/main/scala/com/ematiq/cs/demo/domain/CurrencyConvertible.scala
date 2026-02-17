package com.ematiq.cs.demo.domain

import java.time.Instant

trait CurrencyConvertible[T] {
  def getCurrency(item: T): String
  def getStake(item: T): BigDecimal
  def getDate(item: T): Instant
  def withConvertedStake(item: T, newStake: BigDecimal, newCurrency: String): T
}
