package com.futo.futopay.views

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.futo.futopay.R

class PaymentPostalCodeView : ConstraintLayout {
    private var _isValid = false;

    constructor(context: Context, countryId: String, onSubmit: (String)->Unit): super(context) {
        inflate(context, R.layout.payment_postal_code, this);

        val textSubtext = findViewById<TextView>(R.id.text_subtext);
        val buttonSubmit = findViewById<FrameLayout>(R.id.button_submit);
        val editPostalCode = findViewById<EditText>(R.id.edit_postal_code);
        val regex = when (countryId.lowercase()) {
            "us" -> """^\d{5}(?:[-\s]\d{4})?$""".toRegex()
            "ca" -> """^[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z][ -]?\d[ABCEGHJ-NPRSTV-Z]\d$""".toRegex(RegexOption.IGNORE_CASE)
            else -> null
        }

        editPostalCode.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = editPostalCode.text.toString();

                _isValid = regex?.matches(text) ?: true;
                if (_isValid) {
                    textSubtext.setTextColor(Color.rgb(0x99, 0x99, 0x99));
                    textSubtext.setText("Required to accurately calculate the applicable sales tax");
                    buttonSubmit.alpha = 1.0f;
                } else {
                    textSubtext.setTextColor(Color.rgb(0xFF, 0x00, 0x00));
                    textSubtext.setText("Value is invalid for ${countryId.uppercase()}");
                    buttonSubmit.alpha = 0.4f;
                }
            }
        });

        editPostalCode.hint = when (countryId) {
            "us" -> "Enter ZIP code (e.g., 12345 or 12345-6789)"
            "ca" -> "Enter Postal code (e.g., A1A 1A1)"
            else -> "Enter Postal code"
        }

        if (regex != null) {
            buttonSubmit.alpha = 0.4f;
        }
        buttonSubmit.setOnClickListener {
            if (!_isValid) {
                return@setOnClickListener;
            }
            onSubmit(editPostalCode.text.toString());
        };
    }
}