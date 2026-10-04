package com.example.fisttoexcel

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object ReceiptParser {
    private val money = Regex("(?<!\\d)(?:\\d{1,3}(?:[. ]\\d{3})+|\\d+)(?:,\\d{1,2})?(?:\\s*(?:TL|₺))?(?!\\d)")
    private val companyVknRegex = Regex("(?i)(?<!MÜŞTERİ\\s)(?<!MUSTERI\\s)(?<!ALICI\\s)(?:VKN|VERGİ\\s*NO|VERGİ\\s*NUMARASI|VERGI\\s*NO)\\s*[:#-]?\\s*((?:\\d[\\s.-]?){10,11})")
    private val dateRegex = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b")

    fun parse(text: String, knownSellers: Map<String,String>): ReceiptData {
        val r = ReceiptData(rawText = text)
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        r.vkn = findCompanyVkn(lines)
        r.date = dateRegex.find(text)?.let { parseDate(it.groupValues[0]) }
        r.receiptNo = findReceiptNo(lines)
        r.seller = r.vkn?.let { knownSellers[digits(it)] }
        if (r.seller == null) r.seller = findSeller(lines)
        r.expenseType = inferExpenseType(text)
        r.description = if (Regex("(?i)E[- ]?ARŞİV|E[- ]?ARSIV").containsMatchIn(text)) "E-arşiv" else null

        var totalCandidate: Double? = null
        for (line in lines) {
            val upper = line.uppercase(Locale("tr","TR"))
            val cleanForMoney = line
                .replace(Regex("%\\s*\\d{1,2}"), "")
                .replace(dateRegex, " ")
                .replace(companyVknRegex, " ")
                .replace(Regex("(?i)(?:TCKN|TC\\s*NO)\\s*[:#-]?\\s*(?:\\d[\\s.-]?){11}"), " ")
            val nums = money.findAll(cleanForMoney).mapNotNull { parseMoney(it.value) }.toList()
            if (nums.isEmpty()) continue
            if (upper.contains("GENEL TOPLAM") || upper.contains("TOPLAM") && !upper.contains("KDV")) totalCandidate = nums.last()
            val rate = when {
                Regex("%\\s*20\\b").containsMatchIn(upper) -> 20
                Regex("%\\s*10\\b").containsMatchIn(upper) -> 10
                Regex("%\\s*1\\b").containsMatchIn(upper) -> 1
                Regex("%\\s*0\\b").containsMatchIn(upper) -> 0
                else -> null
            }
            if (rate != null && !upper.contains("KDV") && !upper.contains("VERGİ")) {
                var lineAmount = nums.last()
                val discount = Regex("(?i)(?:%\\s*)?(\\d{1,2}(?:,\\d+)?)\\s*%?\\s*(?:İNDİRİM|INDIRIM)").find(line)
                if (discount != null && nums.size == 1) {
                    val pct = discount.groupValues[1].replace(',', '.').toDoubleOrNull()
                    if (pct != null) lineAmount = lineAmount * (1.0 - pct / 100.0)
                }
                r.grossByVat[rate] = (r.grossByVat[rate] ?: 0.0) + lineAmount
            }
            // Some receipts print VAT summary as "%20 50,00 250,00". Use the larger amount as gross.
            if (upper.contains("KDV") && rate != null && nums.size >= 2) {
                val gross = nums.maxOrNull() ?: 0.0
                if (gross > (r.grossByVat[rate] ?: 0.0)) r.grossByVat[rate] = gross
            }
        }
        r.total = totalCandidate ?: r.grossByVat.values.sum().takeIf { it > 0 }
        r.confidence = score(r)
        return r
    }


    private fun findCompanyVkn(lines: List<String>): String? {
        // Fiş üzerindeki şirket VKN'sini hedefle. Müşteri/alıcı VKN veya TCKN'yi şirket VKN'si olarak alma.
        val labeled = lines.asSequence().mapIndexedNotNull { index, line ->
            val upper = line.uppercase(Locale("tr", "TR"))
            if (upper.contains("MÜŞTERİ VKN") || upper.contains("MUSTERI VKN") ||
                upper.contains("MÜŞTERİ VERGİ") || upper.contains("MUSTERI VERGI") ||
                upper.contains("ALICI VKN") || upper.contains("ALICI VERGİ")) return@mapIndexedNotNull null
            val m = companyVknRegex.find(line) ?: return@mapIndexedNotNull null
            val value = m.groupValues[1].filter(Char::isDigit)
            if (value.length !in 10..11) return@mapIndexedNotNull null
            // Şirket bilgileri genellikle üst bölümde; etiketli satırı önceliklendir.
            Triple(index, value, if (index < (lines.size / 2).coerceAtLeast(1)) 1 else 0)
        }.sortedWith(compareByDescending<Triple<Int,String,Int>> { it.third }.thenBy { it.first })
            .firstOrNull()?.second
        return labeled
    }

    private fun score(r: ReceiptData): Int {
        var s = 0
        if (r.date != null) s += 20
        if (!r.receiptNo.isNullOrBlank()) s += 15
        if (!r.vkn.isNullOrBlank()) s += 20
        if (!r.seller.isNullOrBlank()) s += 15
        if (r.grossByVat.values.sum() > 0) s += 20
        if (!r.expenseType.isNullOrBlank()) s += 10
        return s
    }

    private fun findReceiptNo(lines: List<String>): String? {
        val rx = Regex("(?i)(?:FİŞ\\s*NO|FIS\\s*NO|FİŞ\\s*NUMARASI|FIS\\s*NUMARASI|BELGE\\s*NO|BELGE\\s*NUMARASI)\\s*[:#-]?\\s*([A-Z0-9-]{2,30})")
        return lines.firstNotNullOfOrNull { rx.find(it)?.groupValues?.getOrNull(1) }
    }

    private fun findSeller(lines: List<String>): String? {
        val blocked = listOf("FİŞ", "FIS", "VERGİ", "VKN", "TARİH", "TOPLAM", "KDV", "TUTAR", "ADRES", "TEL", "MÜŞTERİ")
        return lines.take(8).firstOrNull { line ->
            line.length in 3..70 && line.any { it.isLetter() } && blocked.none { line.uppercase(Locale("tr","TR")).contains(it) } && !dateRegex.containsMatchIn(line)
        }
    }

    private fun inferExpenseType(text: String): String {
        val t = text.uppercase(Locale("tr", "TR"))
        fun has(vararg words: String) = words.any { t.contains(it) }
        return when {
            has("AKARYAKIT","BENZİN","BENZIN","MOTORİN","MOTORIN","DİZEL","DIZEL","PETROL","SHELL","OPET","BP ") -> "Yakıt"
            has("RESTORAN","LOKANTA","CAFE","KAFE","YEMEK","YİYECEK","YIYECEK","FAST FOOD","PASTANE","MARKET","BİM","BIM","MİGROS","MIGROS","CARREFOUR","ŞOK","SOK","A101") -> "Gıda"
            has("KIRTASİYE","KIRTASIYE","KALEM","DEFTER","FOTOKOPİ","FOTOKOPI","TONER","YAZICI") -> "Kırtasiye"
            has("GİYİM","GIYIM","TEKSTİL","TEKSTIL","AYAKKABI","GÖMLEK","GOMLEK","PANTOLON","MONT") -> "Giyim"
            has("ECZANE","HASTANE","DOKTOR","KLİNİK","KLINIK","SAĞLIK","SAGLIK","LABORATUVAR") -> "Sağlık"
            has("OTEL","KONAKLAMA","UÇAK","UCAK","THY","PEGASUS","SEYAHAT","BİLET","BILET") -> "Seyahat Gideri"
            has("BAKIM","TAMİR","TAMIR","ONARIM","SERVİS","SERVIS","YEDEK PARÇA","YEDEK PARCA") -> "Bakım-Onarım"
            has("TEMİZLİK","TEMIZLIK","DETERJAN","TEMİZLEME","TEMIZLEME") -> "Temizlik"
            has("KARGO","NAKLİYE","NAKLIYE","KURYE","UPS","FEDEX","DHL","TAKSİ","TAKSI","METRO","OTOBÜS","OTOBUS","MARMARAY","HGS","OTOPARK","PARK") -> "Ulaşım"
            has("REKLAM","GOOGLE ADS","META ADS","FACEBOOK ADS","PAZARLAMA","PROMOSYON") -> "Reklam-Pazarlama"
            has("KİRA","KIRA","EMLAK","OFİS KİRASI","OFIS KIRASI") -> "Kira"
            has("ELEKTRİK","ELEKTRIK","ENERJİ","ENERJI") -> "Elektrik"
            has("SU FATURASI","İSKİ","ISKI","SU VE KANALİZASYON","SU VE KANALIZASYON") -> "Su"
            has("DOĞALGAZ","DOGALGAZ","İGDAŞ","IGDAS") -> "Doğalgaz"
            has("İNTERNET","INTERNET","TELEFON","GSM","TURKCELL","VODAFONE","TÜRK TELEKOM","TURK TELEKOM") -> "İletişim"
            has("YAZILIM","SOFTWARE","LİSANS","LISANS","ABONELİK","ABONELIK","MICROSOFT","ADOBE") -> "Yazılım-Abonelik"
            has("EĞİTİM","EGITIM","KURS","OKUL","SEMİNER","SEMINER","KONFERANS") -> "Eğitim"
            has("SİGORTA","SIGORTA","POLİÇE","POLICE") -> "Sigorta"
            has("VERGİ","VERGI","HARÇ","HARC","NOTER") -> "Vergi-Harç"
            has("BANKA","KOMİSYON","KOMISYON","POS","FAST","EFT","HAVALE") -> "Banka-Finans"
            has("DANIŞMANLIK","DANISMANLIK","MÜŞAVİRLİK","MUSAVIRLIK","MUHASEBE") -> "Danışmanlık"
            has("HUKUK","AVUKAT") -> "Hukuk"
            has("PERSONEL","MAAŞ","MAAS","İŞÇİ","ISCI") -> "Personel"
            has("TEMSİL","TEMSİL VE AĞIRLAMA","TEMSİL VE AGIRLAMA","AĞIRLAMA","AGIRLAMA") -> "Temsil ve Ağırlama"
            has("AKSESUAR","TAKI","ÇANTA","CANTA") -> "Aksesuar"
            else -> "Muhtelif"
        }
    }

    fun parseDate(s: String): Date? {
        return try {
            val clean = s.trim().replace('.', '/').replace('-', '/')
            val parts = clean.split('/')
            if (parts.size != 3) return null
            val year = if (parts[2].length == 2) 2000 + parts[2].toInt() else parts[2].toInt()
            val formatter = SimpleDateFormat("d/M/yyyy", Locale.US).apply { isLenient = false }
            formatter.parse("${parts[0]}/${parts[1]}/$year")
        } catch (_: Exception) { null }
    }

    fun parseMoney(s: String): Double? {
        var x = s.replace("TL", "", true).replace("₺", "").trim().replace(" ", "")
        if (x.contains(',') && x.contains('.')) x = x.replace(".", "").replace(',', '.')
        else if (x.count { it == ',' } == 1) x = x.replace(',', '.')
        else if (x.count { it == '.' } > 1) x = x.replace(".", "")
        return x.toDoubleOrNull()
    }
    private fun digits(s: String) = s.filter(Char::isDigit)
}
