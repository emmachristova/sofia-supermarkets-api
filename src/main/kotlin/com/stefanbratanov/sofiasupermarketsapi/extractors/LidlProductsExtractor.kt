package com.stefanbratanov.sofiasupermarketsapi.extractors

import com.stefanbratanov.sofiasupermarketsapi.common.Log
import com.stefanbratanov.sofiasupermarketsapi.common.Log.Companion.log
import com.stefanbratanov.sofiasupermarketsapi.common.getHtmlDocument
import com.stefanbratanov.sofiasupermarketsapi.interfaces.UrlProductsExtractor
import com.stefanbratanov.sofiasupermarketsapi.model.Product
import java.net.URL
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Log
@Component("Lidl")
class LidlProductsExtractor(
  @Value("\${lidl.base.url}") private val baseUrl: URL,
  val lidlProductExtractor: LidlProductExtractor,
) : UrlProductsExtractor {

  @OptIn(DelicateCoroutinesApi::class)
  override fun extract(url: URL): List<Product> {
    log.info("Processing Lidl URL: {}", url)

    val document = getHtmlDocument(url)

    val defaultCategory = document.title()

    /*
     * Lidl has changed the structure of its product grid.
     *
     * Old format:
     *   <div data-selector="PRODUCT"
     *        image="..."
     *        canonicalUrl="...">
     *
     * New format:
     *   <li ... data-grid-data="[...]">
     *
     * We support both formats so the existing tests continue to work.
     */

    val oldProducts = document.select("div[data-selector=PRODUCT]")

    val deferredProducts =
      if (oldProducts.isNotEmpty()) {
        oldProducts.mapNotNull { productElement ->
          val picUrl = productElement.attr("image").takeIf { imageUrl -> imageUrl.isNotEmpty() }

          productElement
            .attr("canonicalUrl")
            .takeIf { canonicalUrl -> canonicalUrl.isNotEmpty() }
            ?.let { canonicalUrl -> baseUrl.toURI().resolve(canonicalUrl).toURL() }
            ?.let { productUrl ->
              GlobalScope.async {
                lidlProductExtractor
                  .extract(productUrl)
                  ?.copy(category = defaultCategory, picUrl = picUrl)
              }
            }
        }
      } else {
        extractNewFormat(document, defaultCategory)
      }

    return runBlocking { deferredProducts.awaitAll().filterNotNull() }
  }

  @OptIn(DelicateCoroutinesApi::class)
  private fun extractNewFormat(
    document: org.jsoup.nodes.Document,
    defaultCategory: String,
  ): List<Deferred<Product?>> {

    return document.select("[data-grid-data]").mapNotNull { productElement ->
      val gridData = productElement.attr("data-grid-data")

      if (gridData.isBlank()) {
        return@mapNotNull null
      }

      val canonicalUrl =
        Regex(""""canonicalUrl"\s*:\s*"([^"]+)"""")
          .find(gridData)
          ?.groupValues
          ?.getOrNull(1)
          ?.takeIf { it.isNotBlank() }

      val picUrl =
        Regex(""""image"\s*:\s*"([^"]+)"""").find(gridData)?.groupValues?.getOrNull(1)?.takeIf {
          it.isNotBlank()
        }

      val category =
        Regex(""""category"\s*:\s*"([^"]+)"""").find(gridData)?.groupValues?.getOrNull(1)?.takeIf {
          it.isNotBlank()
        } ?: defaultCategory

      canonicalUrl
        ?.let { baseUrl.toURI().resolve(it).toURL() }
        ?.let { productUrl ->
          GlobalScope.async {
            lidlProductExtractor.extract(productUrl)?.copy(category = category, picUrl = picUrl)
          }
        }
    }
  }
}
