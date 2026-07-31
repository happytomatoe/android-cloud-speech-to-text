package com.futo.futopay

import kotlinx.serialization.Serializable

@Serializable
class PaymentIntentInfo(
    val paymentIntent: String,
    val publishableKey: String,
    val customer: String? = null,
    val ephemeralKey: String? = null,
    val purchaseId: String? = null
);