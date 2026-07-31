package com.futo.futopay

import kotlinx.serialization.Serializable

@Serializable
class PaymentStatus(
    val status: Int,
    val purchaseId: String? = null
);