package com.ernesto.myapplication

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.data.TransactionStore
import java.text.SimpleDateFormat
import java.util.*

class TransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        val txtTransactions = findViewById<TextView>(R.id.txtTransactions)

        val transactions = TransactionStore.getTransactions()

        if (transactions.isEmpty()) {
            txtTransactions.text = "No transactions yet"
            return
        }

        val builder = StringBuilder()

        for (t in transactions) {
            val amount = t.amountInCents / 100.0
            val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                .format(Date(t.date))

            builder.append("Type: ${t.paymentType}\n")
            builder.append("Amount: $%.2f\n".format(amount))
            builder.append("Date: $date\n")
            builder.append("Ref: ${t.referenceId}\n")
            builder.append("---------------------\n")
        }

        txtTransactions.text = builder.toString()
    }
}
