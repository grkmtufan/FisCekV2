package com.example.fisttoexcel

import java.util.Date

data class ReceiptData(
    var id: Long = System.currentTimeMillis(),
    var date: Date? = null,
    var receiptNo: String? = null,
    var vkn: String? = null,
    var seller: String? = null,
    var expenseType: String? = null,
    var description: String? = null,
    val grossByVat: MutableMap<Int, Double> = mutableMapOf(),
    var total: Double? = null,
    var confidence: Int = 0,
    var rawText: String = "",
    var createdAt: Long = System.currentTimeMillis()
)
