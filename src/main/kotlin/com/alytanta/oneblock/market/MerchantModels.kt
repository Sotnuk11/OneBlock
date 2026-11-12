package com.alytanta.oneblock.market

import java.util.UUID

data class MerchantLine(val product: String, val quantity: Int, val unitPrice: Long) {
    fun total(): Long = Math.multiplyExact(quantity.toLong(), unitPrice)
}
data class MerchantSale(val id: UUID, val account: UUID, val actor: UUID, val product: String,
    val quantity: Int, val unitPrice: Long, val created: Long, val expires: Long, val sequence: Long, val progressionCredit: Boolean = false) {
    fun total(): Long = Math.multiplyExact(quantity.toLong(), unitPrice)
}
