package com.ematiq.cs.demo.domain

import java.time.Instant

final case class TradeRequest(
    marketId: Long,
    selectionId: Long,
    odds: BigDecimal,
    stake: BigDecimal,
    currency: String,
    date: Instant
)

object TradeRequest {
  implicit val currencyConvertible: CurrencyConvertible[TradeRequest] = new CurrencyConvertible[TradeRequest] {
    def getCurrency(item: TradeRequest): String = item.currency
    def getStake(item: TradeRequest): BigDecimal = item.stake
    def getDate(item: TradeRequest): Instant = item.date
    def withConvertedStake(item: TradeRequest, newStake: BigDecimal, newCurrency: String): TradeRequest =
      item.copy(stake = newStake, currency = newCurrency)
  }
}

final case class TradeResponse(
    marketId: Long,
    selectionId: Long,
    odds: BigDecimal,
    stake: BigDecimal,
    currency: String,
    date: Instant
)

object TradeResponse {
  def from(request: TradeRequest): TradeResponse = TradeResponse(
    marketId = request.marketId,
    selectionId = request.selectionId,
    odds = request.odds,
    stake = request.stake,
    currency = request.currency,
    date = request.date
  )
}
