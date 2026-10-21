package dev.ledgerforge.ledger.service

import java.util.Currency

fun requireIsoCurrency(code: String): String {
    require(code.matches(Regex("^[A-Z]{3}$"))) { "Currency must be an uppercase ISO 4217 code" }
    Currency.getInstance(code)
    return code
}
