package com.ematiq.cs.demo.fx.provider

import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat

private[provider] object CnbApiJson extends DefaultJsonProtocol {
  final case class CnbDailyResponse(rates: Vector[CnbRateRow])
  final case class CnbRateRow(validFor: String, currencyCode: String, amount: BigDecimal, rate: BigDecimal)

  implicit val cnbRateRowFormat: RootJsonFormat[CnbRateRow] = jsonFormat4(CnbRateRow.apply)
  implicit val cnbDailyResponseFormat: RootJsonFormat[CnbDailyResponse] = jsonFormat1(CnbDailyResponse.apply)
}
