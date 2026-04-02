package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.SaleWithRefunds
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionPayment
import com.ernesto.myapplication.engine.MoneyUtils

class TransactionAdapter(
    private val transactions: List<SaleWithRefunds>,
    private val onTransactionClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtPaymentIcon: TextView = itemView.findViewById(R.id.txtPaymentIcon)
        val txtTransactionType: TextView = itemView.findViewById(R.id.txtTransactionType)
        val txtVoidBadge: TextView = itemView.findViewById(R.id.txtVoidBadge)
        val txtPaymentMethod: TextView = itemView.findViewById(R.id.txtPaymentMethod)
        val txtAmount: TextView = itemView.findViewById(R.id.txtAmount)
        val txtOrderNumber: TextView = itemView.findViewById(R.id.txtOrderNumber)
        val txtDateTime: TextView = itemView.findViewById(R.id.txtDateTime)
        val txtTxnNumber: TextView = itemView.findViewById(R.id.txtTxnNumber)
        val txtRefunds: TextView = itemView.findViewById(R.id.txtRefunds)
        val txtVoidedBy: TextView = itemView.findViewById(R.id.txtVoidedBy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val swr = transactions[position]
        val sale = swr.sale
        val refunds = swr.refunds

        val isRefund = sale.type == "REFUND"
        val isPreAuth = sale.type == "PRE_AUTH"
        val isPostAuth = sale.type == "CAPTURE"

        val primaryPayment = sale.payments.firstOrNull()
        val paymentType = primaryPayment?.paymentType ?: sale.paymentType
        val cardBrand = (primaryPayment?.cardBrand ?: sale.cardBrand).uppercase()
        val last4 = primaryPayment?.last4 ?: sale.last4
        val isCash = paymentType.equals("Cash", true)
        val isMix = sale.payments.size > 1 &&
                sale.payments.any { it.paymentType.equals("Cash", true) } &&
                sale.payments.any { !it.paymentType.equals("Cash", true) }

        bindTypeHeader(holder, isCash, isMix, cardBrand, isRefund, isPreAuth, isPostAuth)
        bindPaymentMethod(holder, sale, isCash, cardBrand, last4)
        bindAmount(holder, sale, refunds, isRefund)
        bindOrderNumber(holder, sale)
        bindDateTime(holder, sale)
        bindTxnNumber(holder, sale)
        bindRefunds(holder, refunds)
        bindVoided(holder, sale)

        holder.itemView.setOnClickListener { onTransactionClick(sale) }
    }

    override fun getItemCount(): Int = transactions.size

    private fun bindTypeHeader(
        holder: VH, isCash: Boolean, isMix: Boolean, cardBrand: String,
        isRefund: Boolean, isPreAuth: Boolean, isPostAuth: Boolean
    ) {
        when {
            isRefund -> {
                holder.txtPaymentIcon.text = "↩️"
                holder.txtTransactionType.text = "REFUND"
            }
            isPreAuth -> {
                holder.txtPaymentIcon.text = "💳"
                holder.txtTransactionType.text = "PRE-AUTHORIZATION"
            }
            isPostAuth -> {
                holder.txtPaymentIcon.text = "💳"
                holder.txtTransactionType.text = "CAPTURE"
            }
            isMix -> {
                holder.txtPaymentIcon.text = "💵💳"
                holder.txtTransactionType.text = "MIX PAYMENT"
            }
            isCash -> {
                holder.txtPaymentIcon.text = "💵"
                holder.txtTransactionType.text = "CASH PAYMENT"
            }
            else -> {
                holder.txtPaymentIcon.text = "💳"
                val brandLabel = when {
                    cardBrand.contains("VISA") -> "VISA"
                    cardBrand.contains("MASTER") -> "MASTERCARD"
                    cardBrand.contains("AMEX") || cardBrand.contains("AMERICAN") -> "AMEX"
                    cardBrand.contains("DISCOVER") -> "DISCOVER"
                    else -> "CARD"
                }
                holder.txtTransactionType.text = "$brandLabel PAYMENT"
            }
        }
    }

    private fun bindPaymentMethod(
        holder: VH, sale: Transaction,
        isCash: Boolean, cardBrand: String, last4: String
    ) {
        if (sale.payments.size > 1) {
            val lines = sale.payments.map { p ->
                if (p.paymentType.equals("Cash", true)) "Cash"
                else {
                    val brand = p.cardBrand.takeIf { it.isNotBlank() } ?: p.paymentType.ifBlank { "Card" }
                    if (p.last4.isNotBlank()) "$brand •••• ${p.last4}" else brand
                }
            }
            holder.txtPaymentMethod.text = lines.joinToString(" + ")
        } else {
            holder.txtPaymentMethod.text = when {
                isCash -> "Cash"
                cardBrand.isNotBlank() && last4.isNotBlank() -> {
                    val brand = friendlyBrand(cardBrand)
                    "$brand •••• $last4"
                }
                else -> sale.paymentType.ifBlank { "Card" }
            }
        }
    }

    private fun bindAmount(holder: VH, sale: Transaction, refunds: List<Transaction>, isRefund: Boolean) {
        val saleAmount = sale.amountInCents
        val totalRefunded = refunds.sumOf { kotlin.math.abs(it.amountInCents) }
        val netCents = saleAmount - totalRefunded

        if (isRefund) {
            holder.txtAmount.text = "-${MoneyUtils.centsToDisplay(saleAmount)}"
            holder.txtAmount.setTextColor(Color.parseColor("#E65100"))
        } else {
            holder.txtAmount.text = "+${MoneyUtils.centsToDisplay(netCents.coerceAtLeast(0L))}"
            holder.txtAmount.setTextColor(Color.parseColor("#2E7D32"))
        }
    }

    private fun bindOrderNumber(holder: VH, sale: Transaction) {
        val parts = mutableListOf<String>()
        if (sale.orderNumber > 0L) parts.add("Order #${sale.orderNumber}")
        if (sale.appTransactionNumber > 0L) parts.add("Txn #${sale.appTransactionNumber}")

        if (parts.isNotEmpty()) {
            holder.txtOrderNumber.text = parts.joinToString(" \u2022 ")
            holder.txtOrderNumber.visibility = View.VISIBLE
        } else {
            holder.txtOrderNumber.visibility = View.GONE
        }
    }

    private fun bindDateTime(holder: VH, sale: Transaction) {
        if (sale.date > 0L) {
            holder.txtDateTime.text = DateFormat.format("MMM dd · h:mm a", sale.date).toString()
        } else {
            holder.txtDateTime.text = ""
        }
    }

    private fun bindTxnNumber(holder: VH, sale: Transaction) {
        if (sale.appTransactionNumber > 0L) {
            holder.txtTxnNumber.visibility = View.GONE
            return
        }
        val txnNum = sale.payments.firstOrNull()?.transactionNumber?.takeIf { it.isNotBlank() }
            ?: sale.transactionNumber.takeIf { it.isNotBlank() }

        if (txnNum != null) {
            holder.txtTxnNumber.text = "Txn #$txnNum"
            holder.txtTxnNumber.visibility = View.VISIBLE
        } else {
            holder.txtTxnNumber.visibility = View.GONE
        }
    }

    private fun bindRefunds(holder: VH, refunds: List<Transaction>) {
        if (refunds.isNotEmpty()) {
            val lines = refunds.joinToString("\n") {
                "↩ Refund -${MoneyUtils.centsToDisplay(kotlin.math.abs(it.amountInCents))}"
            }
            holder.txtRefunds.text = lines
            holder.txtRefunds.visibility = View.VISIBLE
        } else {
            holder.txtRefunds.visibility = View.GONE
        }
    }

    private fun bindVoided(holder: VH, sale: Transaction) {
        if (sale.voided) {
            val badge = GradientDrawable().apply {
                setColor(Color.parseColor("#FFEBEE"))
                cornerRadius = 50f
            }
            holder.txtVoidBadge.background = badge
            holder.txtVoidBadge.visibility = View.VISIBLE
            holder.itemView.alpha = 0.6f
            holder.txtAmount.paintFlags = holder.txtAmount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

            if (sale.voidedBy.isNotBlank()) {
                holder.txtVoidedBy.text = "Voided by: ${sale.voidedBy}"
                holder.txtVoidedBy.visibility = View.VISIBLE
            } else {
                holder.txtVoidedBy.visibility = View.GONE
            }
        } else {
            holder.txtVoidBadge.visibility = View.GONE
            holder.txtVoidedBy.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            holder.txtAmount.paintFlags = holder.txtAmount.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun friendlyBrand(raw: String): String {
        val upper = raw.uppercase()
        return when {
            upper.contains("VISA") -> "Visa"
            upper.contains("MASTER") -> "Mastercard"
            upper.contains("AMEX") || upper.contains("AMERICAN") -> "Amex"
            upper.contains("DISCOVER") -> "Discover"
            else -> raw.ifBlank { "Card" }
        }
    }
}
