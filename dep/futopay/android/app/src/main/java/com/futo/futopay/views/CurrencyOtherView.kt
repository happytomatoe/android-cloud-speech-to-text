package com.futo.futopay.views

import android.content.Context
import android.widget.LinearLayout
import com.futo.futopay.R

class CurrencyOtherView: LinearLayout {

    constructor(context: Context, onClick: ()->Unit): super(context) {
        inflate(context, R.layout.payment_currency_other, this)

        setOnClickListener {
            onClick();
        }
    }
}