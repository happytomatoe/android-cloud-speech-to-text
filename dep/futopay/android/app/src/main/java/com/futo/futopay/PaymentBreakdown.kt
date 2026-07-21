package com.futo.futopay

import kotlinx.serialization.Serializable

@Serializable
class PaymentBreakdown(
    val productName: String,
    val productId: String,
    val currency: String,
    val taxPercentage: Double,
    val productPrice: Long,
    val taxPrice: Long,
    val totalPrice: Long
);