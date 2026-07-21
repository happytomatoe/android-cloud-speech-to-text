package com.futo.futopay

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

interface PaymentStatusListener {
    fun onSuccess(purchaseId: String?);
    fun onCancel();
    fun onFailure(error: Throwable);
}

class PaymentManager {
    private val _fragment: Fragment;
    private val _overlayContainer: ViewGroup;
    private val _sheet: PaymentSheet;
    private val _paymentState: PaymentState;
    private val _listener: PaymentStatusListener;

    private var _lastPurchaseId: String? = null;

    constructor(paymentState: PaymentState, fragment: Fragment, overlayContainer: ViewGroup, listener: PaymentStatusListener) {
        _fragment = fragment;
        _paymentState = paymentState;
        _overlayContainer = overlayContainer;
        _listener = listener;

        _sheet = PaymentSheet(_fragment) { paymentSheetResult ->
            when(paymentSheetResult) {
                is PaymentSheetResult.Canceled -> {
                    _listener.onCancel();
                }
                is PaymentSheetResult.Failed -> {
                    _listener.onFailure(paymentSheetResult.error);
                }
                is PaymentSheetResult.Completed -> {
                    _listener.onSuccess(_lastPurchaseId);
                }
            }
        };
    }

    fun startPayment(paymentState: PaymentState, scope: CoroutineScope, productId: String) {
        scope.launch(Dispatchers.IO){
            try{
                val availableCurrencies = _paymentState.getAvailableCurrencies(productId);
                val country = paymentState.getPaymentCountryFromIP()?.let { c -> PaymentConfigurations.COUNTRIES.find { it.id.equals(c, ignoreCase = true) } };
                withContext(Dispatchers.Main) {
                    SlideUpPayment.startPayment(paymentState, _overlayContainer, productId, country, availableCurrencies, { method, request ->
                        when(method) {
                            "stripe" -> startPaymentStripe(productId, request.currency, request.mail, request.country, request.zipcode);
                        }
                    }, { _listener.onCancel() });
                }
            }
            catch(ex: Throwable) {
                Log.e(TAG, "startPayment failed", ex);
                scope.launch(Dispatchers.Main){
                    UIDialogs.showGeneralErrorDialog(_fragment.requireContext(), "Failed to get required payment data (did you grant the app network permission?)", ex, onOk = { _listener.onCancel() });
                }
            }
        }
    }

    private fun startPaymentStripe(productId: String, currency: String, email: String, country: String? = null, zipcode: String? = null) {
        _fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("BuyFragment", "Starting payment");
                val paymentIntentResult = _paymentState.getPaymentIntent(productId, currency, email, country, zipcode);
                val customerConfig = if(paymentIntentResult.customer != null && paymentIntentResult.ephemeralKey != null)
                    PaymentSheet.CustomerConfiguration(paymentIntentResult.customer, paymentIntentResult.ephemeralKey);
                else null;

                _lastPurchaseId = paymentIntentResult.purchaseId;

                PaymentConfiguration.init(_fragment.requireContext(), paymentIntentResult.publishableKey);

                withContext(Dispatchers.Main) {
                    _sheet.presentWithPaymentIntent(
                        paymentIntentResult.paymentIntent, PaymentSheet.Configuration(
                            merchantDisplayName = "FUTO",
                            customer = customerConfig,
                            allowsDelayedPaymentMethods = true,
                            defaultBillingDetails = PaymentSheet.BillingDetails(PaymentSheet.Address(country = country, postalCode = zipcode), email),
                            billingDetailsCollectionConfiguration = PaymentSheet.BillingDetailsCollectionConfiguration(
                                email = PaymentSheet.BillingDetailsCollectionConfiguration.CollectionMode.Always
                            )
                        )
                    );
                }
            }
            catch(ex: Throwable) {
                Log.e(TAG, "Payment failed: ${ex.message}", ex);
                withContext(Dispatchers.Main) {
                    UIDialogs.showGeneralErrorDialog(_fragment.requireContext(), "Payment failed\nIf you are charged you should always receive the key in your mail.", ex);
                }
            }
        }
    }

    //TODO: Determine a good provider

    data class PaymentRequest(
        val productId: String,
        val currency: String,
        val mail: String,
        val country: String? = null,
        val zipcode: String? = null
    );

    companion object {
        private const val TAG = "PaymentManager"
    }
}