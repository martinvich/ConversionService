package com.ematiq.cs.demo.fx.provider

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private[provider] object UrlEncoding {
  def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString)
}
