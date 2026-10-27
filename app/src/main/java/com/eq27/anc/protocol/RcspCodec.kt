package com.eq27.anc.protocol

/**
 * CRC16-варианты. Прошивки Jieli исторически используют семейство CCITT (poly 0x1021),
 * но разные сборки отличаются начальным значением и охватом данных,
 * поэтому у нас пресеты + решатель, который сверяет захваченный кадр со всеми вариантами.
 */
enum class CrcVariant(val label: String) {
    NONE("Без CRC (шлю как есть)"),
    CCITT_FALSE("CRC-16/CCITT-FALSE (1021, init FFFF)"),
    XMODEM("CRC-16/XMODEM (1021, init 0000)"),
    MODBUS("CRC-16/MODBUS (8005 отраж., init FFFF)"),
    ARC("CRC-16/ARC (8005 отраж., init 0000)"),
}

object Crc16 {

    fun compute(data: ByteArray, variant: CrcVariant): Int? = when (variant) {
        CrcVariant.NONE -> null
        CrcVariant.CCITT_FALSE -> msb(data, poly = 0x1021, init = 0xFFFF)
        CrcVariant.XMODEM -> msb(data, poly = 0x1021, init = 0x0000)
        CrcVariant.MODBUS -> lsb(data, reflectedPoly = 0xA001, init = 0xFFFF)
        CrcVariant.ARC -> lsb(data, reflectedPoly = 0xA001, init = 0x0000)
    }

    private fun msb(data: ByteArray, poly: Int, init: Int): Int {
        var crc = init
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor poly) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun lsb(data: ByteArray, reflectedPoly: Int, init: Int): Int {
        var crc = init
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) {
                    (crc ushr 1) xor reflectedPoly
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}

/**
 * Кодек кадров RCSP (протокол вендорских команд чипов JieLi).
 *
 * Формат кадра:
 *
 *   FE DC BA | len(LE16) | cmd(LE16) + payload | crc(LE16)
 *
 * len — длина полезной части (cmd + payload). CRC зависит от прошивки:
 * разные сборки считают её по разным диапазонам — потому и есть решатель [solveCrc].
 *
 * ВАЖНО: если при перехвате hoco.music окажется, что кадры выглядят иначе —
 * приложение умеет слать ЛЮБЫЕ сырые байты без преобразований («Лаборатория → Отправить HEX»).
 */
object RcspCodec {

    const val SYNC0 = 0xFE
    const val SYNC1 = 0xDC
    const val SYNC2 = 0xBA

    /** Собирает полный кадр из номера команды и payload. */
    fun buildFrame(
        cmd: Int,
        payload: ByteArray,
        variant: CrcVariant,
        crcIncludesLen: Boolean,
    ): ByteArray {
        val data = byteArrayOf(
            (cmd and 0xFF).toByte(),
            ((cmd shr 8) and 0xFF).toByte(),
        ) + payload
        return buildFromData(data, variant, crcIncludesLen)
    }

    /** Собирает кадр из готовой полезной части (cmd+payload одним куском). */
    fun buildFromData(
        data: ByteArray,
        variant: CrcVariant,
        crcIncludesLen: Boolean,
    ): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.add(SYNC0.toByte())
        buf.add(SYNC1.toByte())
        buf.add(SYNC2.toByte())
        val len = data.size
        buf.add((len and 0xFF).toByte())
        buf.add(((len shr 8) and 0xFF).toByte())
        buf.addAll(data.toList())
        if (variant != CrcVariant.NONE) {
            val crcInput = if (crcIncludesLen) buf.drop(3).toByteArray() else data
            val crc = Crc16.compute(crcInput, variant) ?: 0
            buf.add((crc and 0xFF).toByte())
            buf.add(((crc shr 8) and 0xFF).toByte())
        }
        return buf.toByteArray()
    }

    /** Разбирает один кадр, возвращает полезную часть (cmd+payload) или null. */
    fun parse(frame: ByteArray): ByteArray? {
        if (frame.size < 8) return null
        if (frame[0] != SYNC0.toByte() || frame[1] != SYNC1.toByte() || frame[2] != SYNC2.toByte()) {
            return null
        }
        val len = frameLength(frame)
        if (frame.size < 5 + len + 2) return null
        return frame.copyOfRange(5, 5 + len)
    }

    private fun frameLength(frame: ByteArray): Int =
        (frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)

    /**
     * Решатель CRC: принимает кадр, захваченный из hoco.music, и проверяет,
     * какой из известных вариантов CRC воспроизводит его контрольную сумму.
     * Так мы за секунды узнаём формат CRC конкретной прошивки.
     */
    fun solveCrc(frameHex: String): String {
        val bytes = frameHex.parseHexLoose()
            ?: return "Не удалось разобрать HEX. Ожидается кадр вида: FE DC BA 04 00 74 01 00 xx xx"
        if (bytes.size < 8) return "Кадр слишком короткий (${bytes.size} Б) — нужен минимум 8 Б."
        val hasSync = bytes[0] == SYNC0.toByte() &&
                bytes[1] == SYNC1.toByte() &&
                bytes[2] == SYNC2.toByte()
        if (!hasSync) {
            return "Кадр не начинается с FE DC BA. Это либо не RCSP-протокол, либо срезано начало. " +
                    "Скопируйте кадр из Wireshark целиком, начиная с fe dc ba."
        }
        val len = frameLength(bytes)
        val expectedSize = 5 + len + 2
        if (bytes.size < expectedSize) {
            return "Кадр обрезан: по заголовку ожидалось $expectedSize Б, получено ${bytes.size} Б."
        }
        val data = bytes.copyOfRange(5, 5 + len)
        val lenBytes = byteArrayOf(bytes[3], bytes[4])
        val crcActual =
            (bytes[5 + len].toInt() and 0xFF) or ((bytes[5 + len + 1].toInt() and 0xFF) shl 8)

        val sb = StringBuilder()
        sb.appendLine("Полезная часть (cmd+payload): ${data.hex()}")
        if (data.size >= 2) {
            val cmd = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            sb.appendLine("Команда: 0x%04X, payload: %d Б".format(cmd, data.size - 2))
        }
        sb.appendLine("CRC в кадре: 0x%04X".format(crcActual))
        var matched = false
        for (v in CrcVariant.entries) {
            if (v == CrcVariant.NONE) continue
            val byData = Crc16.compute(data, v) ?: continue
            val byLenData = Crc16.compute(lenBytes + data, v) ?: continue
            if (byData == crcActual) {
                sb.appendLine("СОВПАЛО: ${v.label}, CRC считается ПО DATA (без len)")
                matched = true
            }
            if (byLenData == crcActual) {
                sb.appendLine("СОВПАЛО: ${v.label}, CRC считается ПО LEN+DATA")
                matched = true
            }
        }
        if (!matched) {
            sb.appendLine("Прямого совпадения нет. Возможные CRC от ваших данных:")
            for (v in CrcVariant.entries) {
                if (v == CrcVariant.NONE) continue
                val byData = Crc16.compute(data, v) ?: 0
                val byLenData = Crc16.compute(lenBytes + data, v) ?: 0
                sb.appendLine(
                    "  ${v.label}: по data=0x%04X, по len+data=0x%04X".format(byData, byLenData),
                )
            }
            sb.appendLine(
                "Если ни один не совпал — CRC считается иначе (например, с sync-байтами или SN-полем). " +
                        "Это не мешает работе: шлите захваченный кадр «как есть», без пересчёта CRC.",
            )
        }
        return sb.toString()
    }
}

// ------------------------------------------------------------------ HEX-утилиты

/** Байты в HEX через пробел: «FE DC BA …». */
fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Терпеливый парсер HEX: понимает пробелы, двоеточия, запятые, дефисы
 * и слитную запись чётной длины («FEDCBA», «fe dc ba»).
 * Возвращает null, если встречен не-HEX символ или нечётное количество цифр.
 */
fun String.parseHexLoose(): ByteArray? {
    val cleaned = replace(",", " ")
        .replace(":", " ")
        .replace("-", " ")
        .replace("0x", " ")
        .replace("0X", " ")
        .trim()
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    val tokens = if (parts.size == 1 && parts[0].length % 2 == 0) {
        parts[0].chunked(2)
    } else {
        parts
    }
    if (tokens.isEmpty()) return null
    val out = ByteArray(tokens.size)
    for (i in tokens.indices) {
        val v = tokens[i].toIntOrNull(16) ?: return null
        if (v < 0 || v > 255) return null
        out[i] = v.toByte()
    }
    return out
}
