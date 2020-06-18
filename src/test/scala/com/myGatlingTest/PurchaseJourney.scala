package com.myGatlingTest

import scala.concurrent.duration._

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.jdbc.Predef._

class PurchaseJourney extends Simulation {

	val httpProtocol = http
		.baseUrl("https://gatling-demostore.com")
		.inferHtmlResources(BlackList(""".*\.js""", """.*\.css""", """.*\.gif""", """.*\.jpeg""", """.*\.jpg""", """.*\.ico""", """.*\.woff""", """.*\.woff2""", """.*\.(t|o)tf""", """.*\.png""", """.*detectportal\.firefox\.com.*"""), WhiteList())
		.acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
		.acceptEncodingHeader("gzip,deflate,sdch")
		.acceptLanguageHeader("en-US,en;q=0.8")

	val feedProduct = csv("data/products.csv").random
	val feedCustomer = csv("data/customers.csv").circular

	val random = new java.util.Random

	def generateFormKey: String = {
		val count   = 16
		val word    = new StringBuilder
		val pattern = """0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ""".toList
		val pLength = pattern.length

		for (i <- 0 to count - 1) {
			word.append(pattern(random.nextInt(pLength)))
		}
		word.toString
	}

	def loadHomepage = {
		feed(feedProduct).feed(feedCustomer)
		.exec(http("Load Homepage")
			.get("/"))
			.exec(session => {
				val form_key = generateFormKey
				session.set("form_key", form_key)
			})
			.exec(addCookie(Cookie("form_key", "${form_key}")))
  		.pause(5 seconds)
	}

	def loadCategoryPage = {
		exec(http("Load Category Page")
			.get("${categoryUrl}"))
  		.pause(5 seconds)
	}

	def loadProductPage = {
		exec(http("Load Product Page")
			.get("${productUrl}"))
  		.pause(5 seconds)
	}

	def addProductToCart = {
		exec(http("Add Item To Cart")
			.post("/checkout/cart/add/uenc/aHR0cHM6Ly9nYXRsaW5nLWRlbW9zdG9yZS5jb20vZWRnZS1yZWQtZXllZ2xhc3MtY2FzZS5odG1s/product/${productId}/")
			.formParam( """product""", "${productId}")
			.formParam( """form_key""", "${form_key}")
			.formParam( """qty""", "1")
			.formParam( """item""", "${productId}")
			.formParam( """selected_configurable_option""", "")
			.formParam( """related_product""", ""))
  		.pause(5 seconds)
	}

	def loadCheckoutPage = {
		exec(http("Load Checkout Page")
			.get("/checkout/")
			.check(regex(""""quoteData":\{"entity_id":"([^"]+)",""").saveAs("cartId")))

  		.exitHereIfFailed
	}

	def estimateInitialShippingMethod = {
		exec(http("Estimate Shipping Method")
			.post("/rest/default/V1/guest-carts/${cartId}/estimate-shipping-methods")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(StringBody("""{"address":{"country_id":"GB","postcode":null}}""")).asJson)

  		.pause(5 seconds)
	}

	def checkEmailAvailable = {
		exec(http("Check Email Available")
			.post("/rest/default/V1/customers/isEmailAvailable")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(StringBody("""{"customerEmail":"${userEmail}"}""")).asJson)
	}

	def submitShippingInfo = {
		exec(http("Submit Shipping Info")
			.post("/rest/default/V1/guest-carts/${cartId}/shipping-information")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(ElFileBody("data/bodies/shippingTemplate.json")).asJson)

		.pause(5 seconds)
	}

	def estimateFinalShippingMethod = {
		exec(http("Estimate Shipping Method")
			.post("/rest/default/V1/guest-carts/${cartId}/estimate-shipping-methods")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(ElFileBody("data/bodies/addressTemplate.json")).asJson)
	}

	def setPaymentInfo = {
		exec(http("Set Payment Information")
			.post("/rest/default/V1/guest-carts/${cartId}/set-payment-information")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(ElFileBody("data/bodies/cartTemplate.json")).asJson)

  	.pause(5 seconds)
	}

	def submitOrder = {
		exec(http("Submit Order")
			.post("/rest/default/V1/guest-carts/${cartId}/payment-information")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Content-Type", "application/json")
			.body(ElFileBody("data/bodies/paymentTemplate.json")).asJson)
	}

	def checkOrderSuccess = {
		exec(http("Load Checkout Success Page")
			.get("/checkout/onepage/success/")
			.check(status.is(200))
			.check(regex("""Your order # is:""")))

		.pause(5 seconds)
	}

	val scn = scenario("Gatling Demostore Purchase Journey")
  	.exec(loadHomepage)

  	.exec(loadCategoryPage)
  	.exec(loadProductPage)

  	.exec(addProductToCart)

  	.group("Fully Load Checkout Page") {
			exec(loadCheckoutPage)
  			.exec(estimateInitialShippingMethod)
		}

  	.group("Submit Shipping Information") {
			exec(checkEmailAvailable)
			.exec(submitShippingInfo)
		}

  	.group("Submit Payment Info To System") {
			exec(estimateFinalShippingMethod)
			.exec(setPaymentInfo)
		}

  	.group("Submit Final Order") {
			exec(submitOrder)
				.exec(checkOrderSuccess)
		}

	setUp(
		scn.inject(
			atOnceUsers(5),
			nothingFor(5 seconds),
			rampUsers(10) during (20 seconds),
			nothingFor(20 seconds),
			constantUsersPerSec(1) during(20 seconds)
	).protocols(httpProtocol))
}