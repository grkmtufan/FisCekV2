package com.example.fisttoexcel

import android.content.Context
import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

/** Persistent JSON store backed by AndroidX AtomicFile. */
class ReceiptStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.filesDir, "receipts.json"))

    @Synchronized
    fun load(): MutableList<ReceiptData> {
        return try {
            val raw = atomicFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(receipts: List<ReceiptData>) {
        val arr = JSONArray()
        receipts.forEach { arr.put(toJson(it)) }
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun clear() {
        atomicFile.delete()
    }

    private fun toJson(r: ReceiptData) = JSONObject().apply {
        put("id", r.id)
        put("date", r.date?.time ?: JSONObject.NULL)
        put("receiptNo", r.receiptNo ?: JSONObject.NULL)
        put("vkn", r.vkn ?: JSONObject.NULL)
        put("seller", r.seller ?: JSONObject.NULL)
        put("expenseType", r.expenseType ?: JSONObject.NULL)
        put("description", r.description ?: JSONObject.NULL)
        put("total", r.total ?: JSONObject.NULL)
        put("createdAt", r.createdAt)
        val vat = JSONObject()
        r.grossByVat.forEach { (rate, amount) -> vat.put(rate.toString(), amount) }
        put("grossByVat", vat)
    }

    private fun fromJson(o: JSONObject): ReceiptData {
        val r = ReceiptData(
            id = o.optLong("id", System.currentTimeMillis()),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
        if (!o.isNull("date")) r.date = Date(o.getLong("date"))
        if (!o.isNull("receiptNo")) r.receiptNo = o.getString("receiptNo")
        if (!o.isNull("vkn")) r.vkn = o.getString("vkn")
        if (!o.isNull("seller")) r.seller = o.getString("seller")
        if (!o.isNull("expenseType")) r.expenseType = o.getString("expenseType")
        if (!o.isNull("description")) r.description = o.getString("description")
        if (!o.isNull("total")) r.total = o.getDouble("total")
        o.optJSONObject("grossByVat")?.let { vat ->
            listOf(0, 1, 10, 20).forEach { rate ->
                if (vat.has(rate.toString())) r.grossByVat[rate] = vat.getDouble(rate.toString())
            }
        }
        return r
    }
}
