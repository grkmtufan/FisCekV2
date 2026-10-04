package com.example.fisttoexcel

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var store: ReceiptStore
    private val approvedReceipts = mutableListOf<ReceiptData>()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var cameraFile: File? = null
    private var lastExcel: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val gallery = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) processUris(uris)
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null && file.isFile) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            processUris(listOf(uri), deleteAfterProcessing = true)
        } else {
            file?.delete()
            cameraFile = null
        }
    }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val source = lastExcel
        if (uri != null && source != null) copyToUri(source, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ReceiptStore(this)
        approvedReceipts.addAll(store.load())
        buildUi()
        if (approvedReceipts.isNotEmpty()) {
            scope.launch {
                lastExcel = withContext(Dispatchers.IO) { ExcelWriter.create(this@MainActivity, approvedReceipts) }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 20)
        }
        val title = TextView(this).apply {
            text = "FişÇek"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        }
        val subtitle = TextView(this).apply {
            text = "Fiş fotoğrafını çek → kontrol et → Onayla → Excel'e kaydet"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }

        fun button(text: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(button("📸 Fiş Çek") { takePhoto() })
        root.addView(button("🖼 Galeriden Fiş Seç") { gallery.launch("image/*") })
        root.addView(button("📊 Excel'i Gör") { viewExcel() })
        root.addView(button("↩️ Son Fişi Geri Al") { confirmUndoLast() })
        root.addView(button("⬇️ Excel'i İndir") { downloadExcel() })
        root.addView(button("🗑 Excel'i Sıfırla") { confirmReset() })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshList()
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        cameraFile = File(dir, "fis_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cameraFile!!)
        camera.launch(uri)
    }

    private fun processUris(uris: List<Uri>, deleteAfterProcessing: Boolean = false) {
        scope.launch {
            status.text = "${uris.size} fiş sırayla okunuyor…"
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                for ((index, uri) in uris.withIndex()) {
                    status.text = "${index + 1}/${uris.size} fiş okunuyor…"
                    try {
                        val parsed = withContext(Dispatchers.IO) {
                            val image = InputImage.fromFilePath(this@MainActivity, uri)
                            val result = recognizer.process(image).awaitResult()
                            ReceiptParser.parse(result.text, ExcelWriter.readSellerMap(this@MainActivity))
                        }
                        showReviewAwait(parsed, index + 1, uris.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Fiş ${index + 1} okunamadı. Fotoğrafı daha net çekip tekrar deneyin.",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        if (deleteAfterProcessing) File(uri.path.orEmpty()).delete()
                    }
                }
            } finally {
                recognizer.close()
                cameraFile?.delete()
                cameraFile = null
                refreshList()
            }
        }
    }

    private suspend fun showReviewAwait(original: ReceiptData, number: Int, total: Int) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 4, 28, 0)
            }
            val hint = TextView(this).apply {
                text = "OCR sonucu. Excel'e kaydetmeden önce bilgileri kontrol edip düzeltebilirsin."
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, 10)
            }
            box.addView(hint)

            fun field(label: String, value: String): EditText = EditText(this).apply {
                hint = label
                setSingleLine(true)
                setText(value)
                layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            }.also { box.addView(it) }

            val date = field("Tarih (GG/AA/YYYY)", original.date?.let { formatDate(it) } ?: "")
            val receiptNo = field("Fiş No", original.receiptNo.orEmpty())
            val vkn = field("VKN / TCKN", original.vkn.orEmpty())
            val seller = field("Satıcı", original.seller.orEmpty())
            val expense = field("Gider Türü", original.expenseType ?: "Muhtelif")
            val description = field("Açıklama", original.description.orEmpty())
            val p0 = field("%0 toplam", moneyText(original.grossByVat[0]))
            val p1 = field("%1 toplam", moneyText(original.grossByVat[1]))
            val p10 = field("%10 toplam", moneyText(original.grossByVat[10]))
            val p20 = field("%20 toplam", moneyText(original.grossByVat[20]))

            val scroll = ScrollView(this).apply { addView(box) }
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Fiş $number/$total — Kontrol Et")
                .setView(scroll)
                .setNegativeButton("Fişi At") { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .setPositiveButton("✓ Onayla", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val fixed = toReceipt(
                        date.text.toString(), receiptNo.text.toString(), vkn.text.toString(), seller.text.toString(),
                        expense.text.toString(), description.text.toString(), p0.text.toString(), p1.text.toString(),
                        p10.text.toString(), p20.text.toString(), original
                    )
                    val check = validate(fixed)
                    if (!check.first) {
                        Toast.makeText(this, check.second, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    scope.launch {
                        try {
                            persistApproved(fixed)
                            dialog.dismiss()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Kayıt/Excel güncellenemedi: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            dialog.setOnCancelListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun toReceipt(
        date: String,
        no: String,
        vkn: String,
        seller: String,
        expense: String,
        description: String,
        p0: String,
        p1: String,
        p10: String,
        p20: String,
        original: ReceiptData
    ): ReceiptData {
        val r = original.copy(id = System.currentTimeMillis(), grossByVat = original.grossByVat.toMutableMap())
        r.date = ReceiptParser.parseDate(date)
        r.receiptNo = no.trim().ifBlank { null }
        r.vkn = vkn.filter(Char::isDigit).ifBlank { null }
        r.seller = seller.trim().ifBlank { null }
        r.expenseType = expense.trim().ifBlank { null }
        r.description = description.trim().ifBlank { null }
        r.grossByVat.clear()
        listOf(0 to p0, 1 to p1, 10 to p10, 20 to p20).forEach { (rate, value) ->
            parseAmount(value)?.takeIf { it >= 0 }?.let { r.grossByVat[rate] = it }
        }
        r.total = r.grossByVat.values.sum().takeIf { it > 0 }
        r.rawText = ""
        r.confidence = 100
        r.createdAt = System.currentTimeMillis()
        return r
    }

    private fun validate(r: ReceiptData): Pair<Boolean, String> {
        if (r.date == null) return false to "Tarih geçersiz. Kontrol edip düzelt."
        if (r.vkn != null && r.vkn!!.length !in 10..11) return false to "VKN/TCKN 10 veya 11 hane olmalı."
        return true to ""
    }

    private suspend fun persistApproved(receipt: ReceiptData) {
        val candidate = approvedReceipts.toMutableList().apply { add(receipt) }
        val excel = withContext(Dispatchers.IO) { ExcelWriter.create(this@MainActivity, candidate) }
        withContext(Dispatchers.IO) { store.save(candidate) }
        approvedReceipts.clear()
        approvedReceipts.addAll(candidate)
        lastExcel = excel
        refreshList()
    }

    private fun refreshList() {
        list.removeAllViews()
        approvedReceipts.forEachIndexed { i, r ->
            val amounts = listOf(0, 1, 10, 20).mapNotNull { rate ->
                r.grossByVat[rate]?.let { "%$rate: ${moneyText(it)} TL" }
            }.joinToString("  ")
            list.addView(TextView(this).apply {
                text = "✓ Fiş ${i + 1}  |  ${r.date?.let { formatDate(it) } ?: ""}  |  ${r.receiptNo.orEmpty()}\nVKN: ${r.vkn.orEmpty()}  |  ${r.seller.orEmpty()}\n$amounts"
                textSize = 15f
                setPadding(12, 16, 12, 16)
            })
        }
        status.text = if (approvedReceipts.isEmpty()) "Henüz onaylanmış fiş yok." else "${approvedReceipts.size} fiş kayıtlı."
    }

    private fun viewExcel() {
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    if (approvedReceipts.isEmpty()) ExcelWriter.existingFile(this@MainActivity)
                    else ExcelWriter.create(this@MainActivity, approvedReceipts)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Excel oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (file == null || !file.isFile) {
                Toast.makeText(this@MainActivity, "Henüz Excel oluşturulmadı.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            lastExcel = file
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Excel açabilecek uyumlu bir uygulama bulunamadı. Excel'i İndir ile kaydedebilirsin.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadExcel() {
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    if (approvedReceipts.isEmpty()) ExcelWriter.existingFile(this@MainActivity)
                    else ExcelWriter.create(this@MainActivity, approvedReceipts)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Excel oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (file == null || !file.isFile) {
                Toast.makeText(this@MainActivity, "Henüz Excel oluşturulmadı.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            lastExcel = file
            createDocument.launch("FişÇek_2026.xlsx")
        }
    }

    private fun copyToUri(source: File, destination: Uri) {
        try {
            contentResolver.openOutputStream(destination)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Hedef dosya açılamadı.")
            Toast.makeText(this, "Excel kaydedildi.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Excel kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmUndoLast() {
        if (approvedReceipts.isEmpty()) {
            Toast.makeText(this, "Geri alınacak kayıt yok.", Toast.LENGTH_SHORT).show()
            return
        }
        val last = approvedReceipts.last()
        MaterialAlertDialogBuilder(this)
            .setTitle("Son fişi geri al?")
            .setMessage("Son eklenen fiş Excel'den ve kayıt listesinden kaldırılacak.\n\n" +
                "Fiş No: ${last.receiptNo.orEmpty()}\n" +
                "Satıcı: ${last.seller.orEmpty()}")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Geri Al") { _, _ ->
                scope.launch {
                    try {
                        val candidate = approvedReceipts.dropLast(1)
                        val excel = withContext(Dispatchers.IO) { ExcelWriter.create(this@MainActivity, candidate) }
                        withContext(Dispatchers.IO) { store.save(candidate) }
                        approvedReceipts.clear()
                        approvedReceipts.addAll(candidate)
                        lastExcel = excel
                        refreshList()
                        Toast.makeText(this@MainActivity, "Son fiş geri alındı.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        // Excel was generated before persistence. If persistence fails, rebuild Excel from the old in-memory list.
                        try {
                            withContext(Dispatchers.IO) { ExcelWriter.create(this@MainActivity, approvedReceipts) }
                        } catch (_: Exception) {
                            // Keep the original error visible; the persistent receipt list was not changed.
                        }
                        Toast.makeText(this@MainActivity, "Geri alma başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Excel'i Sıfırla?")
            .setMessage("Excel'deki tüm kayıtlar silinecek. Bu işlem geri alınamaz.\n\nEmin misin?")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Evet, Sıfırla") { _, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.clear() }
                        approvedReceipts.clear()
                        lastExcel = withContext(Dispatchers.IO) { ExcelWriter.create(this@MainActivity, emptyList()) }
                        refreshList()
                        Toast.makeText(this@MainActivity, "Excel şablona döndürüldü.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Sıfırlama başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun parseAmount(s: String): Double? = ReceiptParser.parseMoney(s.trim())

    private fun moneyText(v: Double?): String =
        v?.let { String.format(Locale.US, "%.2f", it) } ?: ""

    private fun formatDate(date: Date): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(date)

    override fun onDestroy() {
        cameraFile?.delete()
        scope.cancel()
        super.onDestroy()
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        continuation.invokeOnCancellation { cancel() }
    }
