package com.futo.futopay.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.futo.futopay.PaymentBreakdown
import com.futo.futopay.PaymentConfigurations
import com.futo.futopay.R

class PaymentCheckoutView : ConstraintLayout {
    private var _isValid = false;

    private val buttonPay: FrameLayout
    private val textMethod: TextView
    private val textCurrency: TextView
    private val textPostalCode: TextView
    private val textProduct: TextView
    private val textProductPrice: TextView
    private val textTaxPercentage: TextView
    private val textTax: TextView
    private val textTotal: TextView
    private val textCountry: TextView
    private val editEmail: EditText
    private val layoutBreakdown: LinearLayout
    private val layoutLoader: FrameLayout
    private val imageLoader: View
    private val textError: TextView
    private val textEmailSubtext: TextView
    private val textPostalCodeHeader: TextView
    private val buttonChangePostalCode: FrameLayout

    constructor(context: Context, method: String, currency: PaymentConfigurations.CurrencyDescriptor, country: PaymentConfigurations.CountryDescriptor, postalCode: String?, onBuy: (String)->Unit, onChangeCountry: ()->Unit, onChangeCurrency: ()->Unit, onChangePostalCode: ()->Unit): super(context) {
        inflate(context, R.layout.payment_checkout, this);

        buttonPay = findViewById(R.id.button_pay);
        textMethod = findViewById(R.id.text_method)
        textCurrency = findViewById(R.id.text_currency)
        textPostalCode = findViewById(R.id.text_postal_code)
        textPostalCodeHeader = findViewById(R.id.text_postal_code_header)
        buttonChangePostalCode = findViewById(R.id.button_change_postal_code)
        textProduct = findViewById(R.id.text_product)
        textProductPrice = findViewById(R.id.text_product_price)
        textTaxPercentage = findViewById(R.id.text_tax_percentage)
        textTax = findViewById(R.id.text_tax)
        textTotal = findViewById(R.id.text_total)
        textCountry = findViewById(R.id.text_country)
        editEmail = findViewById(R.id.edit_email)
        layoutBreakdown = findViewById(R.id.layout_breakdown)
        layoutLoader = findViewById(R.id.layout_loader)
        textError = findViewById(R.id.text_error)
        imageLoader = findViewById(R.id.image_loader)
        textEmailSubtext = findViewById(R.id.text_email_subtext)

        //findViewById<ImageView>(R.id.image_method).setImageResource(paymentMethod.image);
        textMethod.text = method.replaceFirstChar { it.uppercase() }
        textCountry.text = country.nameEnglish;
        textCurrency.text = currency.id.uppercase();

        findViewById<FrameLayout>(R.id.button_change_country).setOnClickListener {
            onChangeCountry();
        };

        findViewById<FrameLayout>(R.id.button_change_currency).setOnClickListener {
            onChangeCurrency();
        };

        buttonChangePostalCode.setOnClickListener {
            onChangePostalCode();
        };

        val emailRegex = """(?:[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+)*|"(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21\x23-\x5b\x5d-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])*")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21-\x5a\x53-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])+)\])""".toRegex()
        editEmail.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = editEmail.text.toString();
                _isValid = emailRegex.matches(text.lowercase());
                if (_isValid) {
                    textEmailSubtext.setTextColor(Color.rgb(0x99, 0x99, 0x99));
                    textEmailSubtext.setText("Required to send the license key");
                    buttonPay.alpha = 1.0f;
                } else {
                    textEmailSubtext.setTextColor(Color.rgb(0xFF, 0x00, 0x00));
                    textEmailSubtext.setText("Email is invalid");
                    buttonPay.alpha = 0.4f;
                }
            }
        });

        buttonPay.alpha = 0.4f;
        buttonPay.setOnClickListener {
            if (!_isValid) {
                return@setOnClickListener;
            }

            onBuy(editEmail.text.toString());
        }

        setPostalCode(country.id, postalCode);
        setPaymentBreakdown(null);
    }

    private fun setPostalCode(countryId: String, postalCode: String? = null) {
        if (countryId.equals("us", ignoreCase = true) || countryId.equals("ca", ignoreCase = true)) {
            textPostalCode.visibility = View.VISIBLE
            textPostalCodeHeader.visibility = View.VISIBLE
            buttonChangePostalCode.visibility = View.VISIBLE

            if (postalCode != null) {
                textPostalCode.text = postalCode.uppercase();
                textPostalCode.setTextColor(Color.rgb(0xFF, 0xFF, 0xFF))
            } else {
                textPostalCode.text ="Missing";
                textPostalCode.setTextColor(Color.rgb(0xFF, 0, 0))
            }
        } else {
            textPostalCode.visibility = View.GONE
            textPostalCodeHeader.visibility = View.GONE
            buttonChangePostalCode.visibility = View.GONE
        }
    }

    fun setPaymentBreakdown(paymentBreakdown: PaymentBreakdown?) {
        if (paymentBreakdown == null) {
            textError.visibility = View.GONE
            layoutBreakdown.visibility = View.GONE
            layoutLoader.visibility = View.VISIBLE
            imageLoader.visibility = View.VISIBLE
            return;
        }

        val currency = PaymentConfigurations.CURRENCIES.find { it.id == paymentBreakdown.currency };
        val symbol = currency?.symbol ?: "";

        imageLoader.visibility = View.GONE
        layoutLoader.visibility = View.GONE
        textError.visibility = View.GONE
        layoutBreakdown.visibility = View.VISIBLE
        textProduct.text = paymentBreakdown.productName;
        textProductPrice.text = symbol + "%.2f".format((paymentBreakdown.productPrice).toDouble()/100);
        textTaxPercentage.text = "%.2f".format(paymentBreakdown.taxPercentage);
        textTax.text = symbol + "%.2f".format((paymentBreakdown.taxPrice).toDouble()/100);
        textTotal.text = symbol + "%.2f".format((paymentBreakdown.totalPrice).toDouble()/100);
        textCurrency.text = paymentBreakdown.currency.uppercase();
    }

    fun setError(error: String) {
        editEmail.text.clear()
        buttonPay.alpha = 0.4f
        imageLoader.visibility = View.GONE
        layoutLoader.visibility = View.GONE
        layoutBreakdown.visibility = View.GONE
        textError.visibility = View.VISIBLE
        textError.text = error
        editEmail.visibility = View.INVISIBLE
        textEmailSubtext.visibility = View.INVISIBLE
    }
}