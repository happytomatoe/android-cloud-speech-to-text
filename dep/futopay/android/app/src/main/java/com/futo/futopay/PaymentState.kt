package com.futo.futopay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

abstract class PaymentState {
    val REGEX_KEY_FORMAT = Regex("[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}-[a-zA-Z0-9-]{4}");

    private val URL_BASE = if(!isTesting) "https://payment.grayjay.app" else "https://futopay-test.azurewebsites.net";
    private val URL_STATIC_BASE = if(!isTesting) "https://spayment.grayjay.app" else "https://futopay-test.azurewebsites.net";
    private val URL_PAYMENT_STRIPE_INTENT = "${URL_BASE}/api/v1/stripe/paymentintent/payment";
    private val URL_PAYMENT_BREAKDOWN = "${URL_BASE}/api/v1/payment/breakdown";
    private val URL_TIP_INTENT = "${URL_BASE}/api/v1/stripe/paymentintent/tip?amount=";
    private val URL_LOCATION = "${URL_BASE}/api/v1/location";
    private val URL_CURRENCIES = "${URL_STATIC_BASE}/api/v1/payment/currencies";
    private val URL_PRICES = "${URL_STATIC_BASE}/api/v1/payment/prices";
    private val URL_ACTIVATION_URL = "${URL_BASE}/api/v1/activate/";
    private val URL_PAYMENT_STATUS = "${URL_BASE}/api/v1/payment/status/";

    private val _currencyCache = HashMap<String, List<String>>();
    private val _priceCache = HashMap<String, HashMap<String, Long>>();
    private val _validator: LicenseValidator

    var hasPaid: Boolean = false;
    var hasPaidChanged = Event1<Boolean>();

    protected open val isTesting get() = false;

    constructor(validationPublicKey: String) {
        _validator = LicenseValidator(validationPublicKey)
    }

    fun initialize() {
        val license = getPaymentKey();
        if(_validator.validate(license.first, license.second)) {
            hasPaid = true;
            //Initial load does not send change event
        }
    }

    fun clearLicenses() {
        savePaymentKey("", "");
        hasPaid = false;
        hasPaidChanged.emit(false);
    }

    fun setPaymentLicense(anyKey: String): Boolean {
        return (REGEX_KEY_FORMAT.matches(anyKey) && setPaymentLicenseKey(anyKey)) ||
            setPaymentLicenseUrl(anyKey);
    }

    fun setPaymentLicenseKey(key: String): Boolean {
        val activationKeyResponse = httpGET(URL_ACTIVATION_URL + key);
        if(activationKeyResponse.isSuccessful)
            return setPaymentLicenseUrl("${key}/${activationKeyResponse.body!!}");
        else
            throw IllegalStateException("Request failed [${activationKeyResponse.code}]\n" + activationKeyResponse.body);
    }

    fun setPaymentLicenseUrl(url: String): Boolean {
        val protocolIndex = url.indexOf("://");
        var urlToUse = if(protocolIndex == -1) {
            url;
        } else {
            url.substring(protocolIndex + "://".length);
        }

        if(urlToUse.startsWith("license/", true))
            urlToUse = urlToUse.substring("license/".length);

        val parts = urlToUse.split("/");
        if(parts.size != 2)
            return false;

        val licenseKey = parts[0];
        val activationKey = parts[1];

        return setPaymentLicense(licenseKey, activationKey);
    }
    fun setPaymentLicense(licenseKey: String, activationKey: String): Boolean {
        if(_validator.validate(licenseKey, activationKey)) {
            savePaymentKey(licenseKey, activationKey);
            hasPaid = true;
            hasPaidChanged.emit(true);
            return true;
        }
        else
            return false;
    }

    fun getAvailableCurrencies(productId: String): List<String> {
        synchronized(_currencyCache) {
            if(_currencyCache.containsKey(productId))
                return _currencyCache[productId]!!;
        }
        val url = URL_CURRENCIES + "?productId=" + productId;
        val result = httpGET(url);
        if(!result.isSuccessful)
            throw IllegalStateException("Could not get currencies [${result.code}]:\n" + result.body);
        if(result.body == null)
            throw IllegalStateException("Could not get currencies:\nEmpty response");

        val listResult = _json.decodeFromString<List<String>>(result.body!!);
        synchronized(_currencyCache) {
            _currencyCache[productId] = listResult;
            return _currencyCache[productId]!!;
        }
    }
    fun getAvailableCurrencyPrices(productId: String): Map<String, Long> {
        synchronized(_priceCache) {
            if(_priceCache.containsKey(productId))
                return _priceCache[productId]!!;
        }
        val url = URL_PRICES + "?productId=" + productId;
        val result = httpGET(url);
        if(!result.isSuccessful)
            throw IllegalStateException("Could not get currencies [${result.code}]:\n" + result.body);
        if(result.body == null)
            throw IllegalStateException("Could not get currencies:\nEmpty response");

        val listResult = _json.decodeFromString<HashMap<String, Long>>(result.body!!);
        synchronized(_priceCache) {
            _priceCache[productId] = listResult;
            return _priceCache[productId]!!;
        }
    }
    fun getPaymentBreakdown(productId: String, currency: String, country: String? = null, zipcode: String? = null): PaymentBreakdown {
        val url = URL_PAYMENT_BREAKDOWN +
                "?productId=" + productId +
                "&currency=" + currency +
                (if(country != null) "&country=" + country else "") +
                (if(country != null && zipcode != null) "&zipcode=" + zipcode else "");
        val result = httpGET(url);
        if(!result.isSuccessful)
            throw IllegalStateException("Could not get payment breakdown [${result.code}]:\n" + result.body);
        if(result.body == null)
            throw IllegalStateException("Could not get payment breakdown:\nEmpty response");
        return _json.decodeFromString(result.body!!);
    }
    fun getPaymentIntent(productId: String, currency: String, email: String, country: String? = null, zipcode: String? = null): PaymentIntentInfo {
        val result = httpGET(URL_PAYMENT_STRIPE_INTENT +
            "?productId=" + productId +
                "&currency=" + currency +
                "&email=" + email +
                (if(country != null) "&country=" + country else "") +
                (if(country != null && zipcode != null) "&zipcode=" + zipcode else "")
        );
        if(!result.isSuccessful)
            throw IllegalStateException("Could not get payment intent:\n" + result.body);
        if(result.body == null)
            throw IllegalStateException("Could not get payment intent:\nEmpty response");
        return _json.decodeFromString(result.body!!);
    }

    fun getPaymentStatus(purchaseId: String): PaymentStatus {
        val result = httpGET(URL_PAYMENT_STATUS + purchaseId);
        if(!result.isSuccessful)
            throw IllegalStateException("Could not get payment intent:\n" + result.body);
        if(result.body == null)
            throw IllegalStateException("Could not get payment intent:\nEmpty response");
        return _json.decodeFromString(result.body!!);
    }


    fun getPaymentCountryFromIP(): String? {
        val urlString = "https://freeipapi.com/api/json"

        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        val json = response.toString();

        val ipInfoObj = JsonParser.parseString(json) as JsonObject;
        if(ipInfoObj.has("countryCode"))
            return ipInfoObj.get("countryCode").asString;
        return null;
    }

    private fun httpGET(urlStr: String): HttpResp {
        val url = URL(urlStr);
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        return HttpResp(connection.responseCode, response.toString());
    }
    abstract fun savePaymentKey(licenseKey: String, licenseActivation: String);
    abstract fun getPaymentKey(): Pair<String, String>;

    companion object {
        private val _json = Json { ignoreUnknownKeys = true };
    }

    private class HttpResp(
        val code: Int,
        val body: String?
    )
    {
        val isSuccessful get() = code >= 200 && code < 300;
    }
}