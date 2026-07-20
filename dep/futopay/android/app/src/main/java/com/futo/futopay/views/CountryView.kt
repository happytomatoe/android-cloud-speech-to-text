package com.futo.futopay.views

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.futo.futopay.PaymentConfigurations
import com.futo.futopay.R
import com.futo.futopay.getCountryDrawable

class CountryView : ConstraintLayout {
    private val _image: ImageView;
    private val _abbr: TextView;
    private val _name: TextView;

    constructor(context: Context): super(context) {
        inflate(context, R.layout.payment_country, this);

        _image = findViewById(R.id.image_flag);
        _abbr = findViewById(R.id.text_currency_abbr);
        _name = findViewById(R.id.text_currency_name);
    }

    fun bind(currencyDescriptor: PaymentConfigurations.CountryDescriptor, onClick: (String)->Unit) {
        if (!currencyDescriptor.flag.isNullOrEmpty()) {
            _image.setImageDrawable(getCountryDrawable(context, currencyDescriptor.flag));
        } else {
            _image.setImageResource(0);
        }

        _abbr.text = currencyDescriptor.nameEnglish;
        _name.text = "(${currencyDescriptor.id.uppercase()}) ${currencyDescriptor.nameNative}";

        setOnClickListener {
            onClick(currencyDescriptor.id);
        }
    }
}