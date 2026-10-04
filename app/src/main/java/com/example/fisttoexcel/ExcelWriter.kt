package com.example.fisttoexcel

import android.content.Context
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellReference
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExcelWriter {
    private const val SHEET_NAME = "AĞUSTOS 2026 Fişler"
    private const val FIRST_DATA_ROW = 4
    private const val OUTPUT_NAME = "fiscek.xlsx"
    private const val TEMPLATE_ASSET = "fis_sablon.xlsx"

    /** Builds a fresh workbook from the supplied template. No old receipt rows are reused. */
    fun create(context: Context, receipts: List<ReceiptData>): File {
        val workbook = context.assets.open(TEMPLATE_ASSET).use { XSSFWorkbook(it) }
        val sheet = workbook.getSheet(SHEET_NAME) ?: workbook.getSheetAt(1)
        val dataSheet = workbook.getSheet("DATA")
        val sellers = readSellerMap(dataSheet)
        val totalRow = FIRST_DATA_ROW + receipts.size

        prepareSheet(sheet, totalRow)
        receipts.forEachIndexed { index, receipt ->
            val excelRow = FIRST_DATA_ROW + index
            val row = sheet.getRow(excelRow - 1) ?: sheet.createRow(excelRow - 1)
            writeReceipt(row, excelRow, receipt, sellers)
        }
        writeTotalRow(sheet, totalRow)
        workbook.setForceFormulaRecalculation(true)

        val outputDir = File(context.filesDir, "exports").apply { mkdirs() }
        val temp = File(outputDir, "$OUTPUT_NAME.tmp")
        val output = File(outputDir, OUTPUT_NAME)
        FileOutputStream(temp).use { workbook.write(it) }
        workbook.close()
        if (output.exists() && !output.delete()) {
            temp.delete()
            throw IllegalStateException("Eski Excel dosyası değiştirilemedi.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IllegalStateException("Excel dosyası oluşturulamadı.")
        }
        return output
    }

    fun existingFile(context: Context): File? = File(File(context.filesDir, "exports"), OUTPUT_NAME).takeIf { it.isFile }

    fun readSellerMap(context: Context): Map<String, String> =
        context.assets.open(TEMPLATE_ASSET).use { input ->
            XSSFWorkbook(input).use { workbook -> readSellerMap(workbook.getSheet("DATA")) }
        }

    private fun prepareSheet(sheet: Sheet, totalRow: Int) {
        // Locate the original total row from the template instead of trusting a hardcoded row number.
        val templateTotalIndex = findTotalRow(sheet)
        val templateTotalExcelRow = templateTotalIndex + 1
        val originalTotal = sheet.getRow(templateTotalIndex)

        if (totalRow > templateTotalExcelRow) {
            val shift = totalRow - templateTotalExcelRow
            sheet.shiftRows(templateTotalIndex, sheet.lastRowNum, shift, true, false)
        } else if (originalTotal != null) {
            val target = sheet.getRow(totalRow - 1) ?: sheet.createRow(totalRow - 1)
            if (target !== originalTotal) copyRowStructure(originalTotal, target)
        }

        // Rows used for receipt data are always rebuilt from the template data-row style.
        val styleSource = sheet.getRow(FIRST_DATA_ROW - 1)
        for (excelRow in FIRST_DATA_ROW until totalRow) {
            val row = sheet.getRow(excelRow - 1) ?: sheet.createRow(excelRow - 1)
            copyRowStructure(styleSource, row)
            clearCells(row)
            setRowFormulas(row, excelRow)
        }

        // Remove the old trailing template area when the dynamic total row moves upward.
        if (totalRow <= templateTotalExcelRow) {
            for (excelRow in (totalRow + 1)..templateTotalExcelRow) {
                sheet.getRow(excelRow - 1)?.let { clearCells(it) }
            }
        }

        val total = sheet.getRow(totalRow - 1) ?: sheet.createRow(totalRow - 1)
        if (totalRow > templateTotalExcelRow) {
            // The original total row was shifted into the target position.
            // Its styles are therefore already present.
        } else if (originalTotal != null && total !== originalTotal) {
            // Styles were copied before the old template row was cleared.
            copyRowStructure(originalTotal, total)
        }
        clearCells(total)
        total.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("TOPLAM")
    }

    private fun findTotalRow(sheet: Sheet): Int {
        for (i in 0..sheet.lastRowNum) {
            val value = sheet.getRow(i)?.getCell(0)?.stringCellValue?.trim()
            if (value.equals("TOPLAM", ignoreCase = true)) return i
        }
        throw IllegalStateException("Şablonda TOPLAM satırı bulunamadı.")
    }

    private fun writeTotalRow(sheet: Sheet, totalRow: Int) {
        val row = sheet.getRow(totalRow - 1) ?: sheet.createRow(totalRow - 1)
        row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("TOPLAM")
        for (c in 6..9) {
            row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula =
                "SUM(${col(c)}$FIRST_DATA_ROW:${col(c)}${totalRow - 1})"
        }
        row.getCell(10, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula =
            "SUM(G$FIRST_DATA_ROW:J${totalRow - 1})-SUM(L$FIRST_DATA_ROW:O${totalRow - 1})"
        for (c in 11..14) {
            row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula =
                "SUM(${col(c)}$FIRST_DATA_ROW:${col(c)}${totalRow - 1})"
        }
        row.getCell(15, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula =
            "SUM(L$FIRST_DATA_ROW:O${totalRow - 1})"
        row.getCell(16, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula =
            "SUM(G$FIRST_DATA_ROW:J${totalRow - 1})"
    }

    private fun readSellerMap(data: Sheet?): Map<String, String> {
        val sellers = mutableMapOf<String, String>()
        val formatter = DataFormatter()
        if (data == null) return sellers
        for (idx in 0..data.lastRowNum) {
            val row = data.getRow(idx) ?: continue
            val vkn = row.getCell(1)?.let { formatter.formatCellValue(it) }?.filter(Char::isDigit).orEmpty()
            val seller = cellText(row.getCell(2)).trim()
            if (vkn.isNotBlank() && seller.isNotBlank()) sellers[vkn] = seller
        }
        return sellers
    }

    private fun writeReceipt(row: Row, excelRow: Int, rec: ReceiptData, sellers: Map<String, String>) {
        row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(rec.description ?: "")
        rec.date?.let { row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(it) }
            ?: row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank()
        row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(rec.receiptNo ?: "")

        val vknCell = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
        vknCell.setCellValue(rec.vkn ?: "")
        vknCell.setCellType(CellType.STRING)

        val seller = rec.vkn?.let { sellers[it] } ?: rec.seller.orEmpty()
        row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(seller)
        row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(rec.expenseType ?: "")
        setAmountOrBlank(row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), rec.grossByVat[0])
        setAmountOrBlank(row.getCell(7, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), rec.grossByVat[1])
        setAmountOrBlank(row.getCell(8, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), rec.grossByVat[10])
        setAmountOrBlank(row.getCell(9, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), rec.grossByVat[20])
        setRowFormulas(row, excelRow)
    }

    private fun setRowFormulas(row: Row, excelRow: Int) {
        row.getCell(10, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "SUM(G$excelRow:J$excelRow)-SUM(L$excelRow:O$excelRow)"
        row.getCell(11, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank()
        row.getCell(12, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "H$excelRow/101"
        row.getCell(13, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "I$excelRow/11"
        row.getCell(14, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "J$excelRow/6"
        row.getCell(15, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "SUM(L$excelRow:O$excelRow)"
        row.getCell(16, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).cellFormula = "SUM(G$excelRow:J$excelRow)"
    }

    private fun copyRowStructure(source: Row?, target: Row) {
        if (source == null) return
        target.height = source.height
        for (c in 0 until source.lastCellNum.coerceAtLeast(17)) {
            val src = source.getCell(c) ?: continue
            val dst = target.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
            dst.cellStyle = src.cellStyle
        }
    }

    private fun clearCells(row: Row) {
        for (c in 0..16) row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank()
    }

    private fun setAmountOrBlank(cell: Cell, value: Double?) {
        if (value != null && value > 0) cell.setCellValue(value) else cell.setBlank()
    }

    private fun cellText(cell: Cell?): String = when (cell?.cellType) {
        CellType.STRING -> cell.stringCellValue
        CellType.NUMERIC -> java.math.BigDecimal.valueOf(cell.numericCellValue).toPlainString()
        CellType.FORMULA -> cell.toString()
        else -> cell?.toString().orEmpty()
    }

    private fun col(index: Int) = CellReference.convertNumToColString(index)
}
