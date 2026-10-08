package com.auralis.music.data.parser

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object QrcDecrypter {

    private val DEC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val SBOX1 = intArrayOf(
        14,  4, 13,  1,  2, 15, 11,  8,  3, 10,  6, 12,  5,  9,  0,  7,
         0, 15,  7,  4, 14,  2, 13,  1, 10,  6, 12, 11,  9,  5,  3,  8,
         4,  1, 14,  8, 13,  6,  2, 11, 15, 12,  9,  7,  3, 10,  5,  0,
        15, 12,  8,  2,  4,  9,  1,  7,  5, 11,  3, 14, 10,  0,  6, 13
    )

    private val SBOX2 = intArrayOf(
        15,  1,  8, 14,  6, 11,  3,  4,  9,  7,  2, 13, 12,  0,  5, 10,
         3, 13,  4,  7, 15,  2,  8, 15, 12,  0,  1, 10,  6,  9, 11,  5,
         0, 14,  7, 11, 10,  4, 13,  1,  5,  8, 12,  6,  9,  3,  2, 15,
        13,  8, 10,  1,  3, 15,  4,  2, 11,  6,  7, 12,  0,  5, 14,  9
    )

    private val SBOX3 = intArrayOf(
        10,  0,  9, 14,  6,  3, 15,  5,  1, 13, 12,  7, 11,  4,  2,  8,
        13,  7,  0,  9,  3,  4,  6, 10,  2,  8,  5, 14, 12, 11, 15,  1,
        13,  6,  4,  9,  8, 15,  3,  0, 11,  1,  2, 12,  5, 10, 14,  7,
         1, 10, 13,  0,  6,  9,  8,  7,  4, 15, 14,  3, 11,  5,  2, 12
    )

    private val SBOX4 = intArrayOf(
         7, 13, 14,  3,  0,  6,  9, 10,  1,  2,  8,  5, 11, 12,  4, 15,
        13,  8, 11,  5,  6, 15,  0,  3,  4,  7,  2, 12,  1, 10, 14,  9,
        10,  6,  9,  0, 12, 11,  7, 13, 15,  1,  3, 14,  5,  2,  8,  4,
         3, 15,  0,  6, 10, 10, 13,  8,  9,  4,  5, 11, 12,  7,  2, 14
    )

    private val SBOX5 = intArrayOf(
         2, 12,  4,  1,  7, 10, 11,  6,  8,  5,  3, 15, 13,  0, 14,  9,
        14, 11,  2, 12,  4,  7, 13,  1,  5,  0, 15, 10,  3,  9,  8,  6,
         4,  2,  1, 11, 10, 13,  7,  8, 15,  9, 12,  5,  6,  3,  0, 14,
        11,  8, 12,  7,  1, 14,  2, 13,  6, 15,  0,  9, 10,  4,  5,  3
    )

    private val SBOX6 = intArrayOf(
        12,  1, 10, 15,  9,  2,  6,  8,  0, 13,  3,  4, 14,  7,  5, 11,
        10, 15,  4,  2,  7, 12,  9,  5,  6,  1, 13, 14,  0, 11,  3,  8,
         9, 14, 15,  5,  2,  8, 12,  3,  7,  0,  4, 10,  1, 13, 11,  6,
         4,  3,  2, 12,  9,  5, 15, 10, 11, 14,  1,  7,  6,  0,  8, 13
    )

    private val SBOX7 = intArrayOf(
         4, 11,  2, 14, 15,  0,  8, 13,  3, 12,  9,  7,  5, 10,  6,  1,
        13,  0, 11,  7,  4,  9,  1, 10, 14,  3,  5, 12,  2, 15,  8,  6,
         1,  4, 11, 13, 12,  3,  7, 14, 10, 15,  6,  8,  0,  5,  9,  2,
         6, 11, 13,  8,  1,  4, 10,  7,  9,  5,  0, 15, 14,  2,  3, 12
    )

    private val SBOX8 = intArrayOf(
        13,  2,  8,  4,  6, 15, 11,  1, 10,  9,  3, 14,  5,  0, 12,  7,
         1, 15, 13,  8, 10,  3,  7,  4, 12,  5,  6, 11,  0, 14,  9,  2,
         7, 11,  4,  1,  9, 12, 14,  2,  0,  6, 10, 13, 15,  3,  5,  8,
         2,  1, 14,  7,  4, 10,  8, 13, 15, 12,  9,  0,  3,  5,  6, 11
    )

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16,  8,  0, 57, 49, 41, 33, 25, 17,
         9,  1, 58, 50, 42, 34, 26, 18, 10,  2, 59, 51, 43, 35
    )

    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14,  6, 61, 53, 45, 37, 29, 21,
        13,  5, 60, 52, 44, 36, 28, 20, 12,  4, 27, 19, 11,  3
    )

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23,  0,  4,  2, 27, 14,  5, 20,  9,
        22, 18, 11,  3, 25,  7, 15,  6, 26, 19, 12,  1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )

    @Suppress("NOTHING_TO_INLINE")
    private inline fun bitnum(a: ByteArray, b: Int, c: Int): Int {
        val byteIdx = (b / 32) * 4 + 3 - (b % 32) / 8
        val bit = ((a[byteIdx].toInt() and 0xFF) ushr (7 - (b % 8))) and 0x01
        return bit shl c
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun bitnumIntR(a: Int, b: Int, c: Int): Int {
        return ((a ushr (31 - b)) and 0x01) shl c
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun bitnumIntL(a: Int, b: Int, c: Int): Int {
        return ((a shl b) and 0x80000000.toInt()) ushr c
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun sboxbit(a: Int): Int {
        return (a and 0x20) or ((a and 0x1F) ushr 1) or ((a and 0x01) shl 4)
    }

    private fun ip(inp: ByteArray): IntArray {
        val s0 = bitnum(inp, 57, 31) or bitnum(inp, 49, 30) or bitnum(inp, 41, 29) or bitnum(inp, 33, 28) or
                 bitnum(inp, 25, 27) or bitnum(inp, 17, 26) or bitnum(inp, 9, 25) or bitnum(inp, 1, 24) or
                 bitnum(inp, 59, 23) or bitnum(inp, 51, 22) or bitnum(inp, 43, 21) or bitnum(inp, 35, 20) or
                 bitnum(inp, 27, 19) or bitnum(inp, 19, 18) or bitnum(inp, 11, 17) or bitnum(inp, 3, 16) or
                 bitnum(inp, 61, 15) or bitnum(inp, 53, 14) or bitnum(inp, 45, 13) or bitnum(inp, 37, 12) or
                 bitnum(inp, 29, 11) or bitnum(inp, 21, 10) or bitnum(inp, 13, 9) or bitnum(inp, 5, 8) or
                 bitnum(inp, 63, 7) or bitnum(inp, 55, 6) or bitnum(inp, 47, 5) or bitnum(inp, 39, 4) or
                 bitnum(inp, 31, 3) or bitnum(inp, 23, 2) or bitnum(inp, 15, 1) or bitnum(inp, 7, 0)

        val s1 = bitnum(inp, 56, 31) or bitnum(inp, 48, 30) or bitnum(inp, 40, 29) or bitnum(inp, 32, 28) or
                 bitnum(inp, 24, 27) or bitnum(inp, 16, 26) or bitnum(inp, 8, 25) or bitnum(inp, 0, 24) or
                 bitnum(inp, 58, 23) or bitnum(inp, 50, 22) or bitnum(inp, 42, 21) or bitnum(inp, 34, 20) or
                 bitnum(inp, 26, 19) or bitnum(inp, 18, 18) or bitnum(inp, 10, 17) or bitnum(inp, 2, 16) or
                 bitnum(inp, 60, 15) or bitnum(inp, 52, 14) or bitnum(inp, 44, 13) or bitnum(inp, 36, 12) or
                 bitnum(inp, 28, 11) or bitnum(inp, 20, 10) or bitnum(inp, 12, 9) or bitnum(inp, 4, 8) or
                 bitnum(inp, 62, 7) or bitnum(inp, 54, 6) or bitnum(inp, 46, 5) or bitnum(inp, 38, 4) or
                 bitnum(inp, 30, 3) or bitnum(inp, 22, 2) or bitnum(inp, 14, 1) or bitnum(inp, 6, 0)

        return intArrayOf(s0, s1)
    }

    private fun invIp(s0: Int, s1: Int, out: ByteArray) {
        out[3] = (bitnumIntR(s1, 7, 7) or bitnumIntR(s0, 7, 6) or bitnumIntR(s1, 15, 5) or
                  bitnumIntR(s0, 15, 4) or bitnumIntR(s1, 23, 3) or bitnumIntR(s0, 23, 2) or
                  bitnumIntR(s1, 31, 1) or bitnumIntR(s0, 31, 0)).toByte()

        out[2] = (bitnumIntR(s1, 6, 7) or bitnumIntR(s0, 6, 6) or bitnumIntR(s1, 14, 5) or
                  bitnumIntR(s0, 14, 4) or bitnumIntR(s1, 22, 3) or bitnumIntR(s0, 22, 2) or
                  bitnumIntR(s1, 30, 1) or bitnumIntR(s0, 30, 0)).toByte()

        out[1] = (bitnumIntR(s1, 5, 7) or bitnumIntR(s0, 5, 6) or bitnumIntR(s1, 13, 5) or
                  bitnumIntR(s0, 13, 4) or bitnumIntR(s1, 21, 3) or bitnumIntR(s0, 21, 2) or
                  bitnumIntR(s1, 29, 1) or bitnumIntR(s0, 29, 0)).toByte()

        out[0] = (bitnumIntR(s1, 4, 7) or bitnumIntR(s0, 4, 6) or bitnumIntR(s1, 12, 5) or
                  bitnumIntR(s0, 12, 4) or bitnumIntR(s1, 20, 3) or bitnumIntR(s0, 20, 2) or
                  bitnumIntR(s1, 28, 1) or bitnumIntR(s0, 28, 0)).toByte()

        out[7] = (bitnumIntR(s1, 3, 7) or bitnumIntR(s0, 3, 6) or bitnumIntR(s1, 11, 5) or
                  bitnumIntR(s0, 11, 4) or bitnumIntR(s1, 19, 3) or bitnumIntR(s0, 19, 2) or
                  bitnumIntR(s1, 27, 1) or bitnumIntR(s0, 27, 0)).toByte()

        out[6] = (bitnumIntR(s1, 2, 7) or bitnumIntR(s0, 2, 6) or bitnumIntR(s1, 10, 5) or
                  bitnumIntR(s0, 10, 4) or bitnumIntR(s1, 18, 3) or bitnumIntR(s0, 18, 2) or
                  bitnumIntR(s1, 26, 1) or bitnumIntR(s0, 26, 0)).toByte()

        out[5] = (bitnumIntR(s1, 1, 7) or bitnumIntR(s0, 1, 6) or bitnumIntR(s1, 9, 5) or
                  bitnumIntR(s0, 9, 4) or bitnumIntR(s1, 17, 3) or bitnumIntR(s0, 17, 2) or
                  bitnumIntR(s1, 25, 1) or bitnumIntR(s0, 25, 0)).toByte()

        out[4] = (bitnumIntR(s1, 0, 7) or bitnumIntR(s0, 0, 6) or bitnumIntR(s1, 8, 5) or
                  bitnumIntR(s0, 8, 4) or bitnumIntR(s1, 16, 3) or bitnumIntR(s0, 16, 2) or
                  bitnumIntR(s1, 24, 1) or bitnumIntR(s0, 24, 0)).toByte()
    }

    private fun f(state: Int, key: ByteArray): Int {
        val t1 = bitnumIntL(state, 31, 0) or ((state and 0xF0000000.toInt()) ushr 1) or bitnumIntL(state, 4, 5) or
                 bitnumIntL(state, 3, 6) or ((state and 0x0F000000) ushr 3) or bitnumIntL(state, 8, 11) or
                 bitnumIntL(state, 7, 12) or ((state and 0x00F00000) ushr 5) or bitnumIntL(state, 12, 17) or
                 bitnumIntL(state, 11, 18) or ((state and 0x000F0000) ushr 7) or bitnumIntL(state, 16, 23)

        val t2 = bitnumIntL(state, 15, 0) or ((state and 0x0000F000) shl 15) or bitnumIntL(state, 20, 5) or
                 bitnumIntL(state, 19, 6) or ((state and 0x00000F00) shl 13) or bitnumIntL(state, 24, 11) or
                 bitnumIntL(state, 23, 12) or ((state and 0x000000F0) shl 11) or bitnumIntL(state, 28, 17) or
                 bitnumIntL(state, 27, 18) or ((state and 0x0000000F) shl 9) or bitnumIntL(state, 0, 23)

        val k0 = key[0].toInt() and 0xFF
        val k1 = key[1].toInt() and 0xFF
        val k2 = key[2].toInt() and 0xFF
        val k3 = key[3].toInt() and 0xFF
        val k4 = key[4].toInt() and 0xFF
        val k5 = key[5].toInt() and 0xFF

        val l0 = ((t1 ushr 24) and 0xFF) xor k0
        val l1 = ((t1 ushr 16) and 0xFF) xor k1
        val l2 = ((t1 ushr 8) and 0xFF) xor k2
        val l3 = ((t2 ushr 24) and 0xFF) xor k3
        val l4 = ((t2 ushr 16) and 0xFF) xor k4
        val l5 = ((t2 ushr 8) and 0xFF) xor k5

        var res = (SBOX1[sboxbit(l0 ushr 2)] shl 28) or
                  (SBOX2[sboxbit(((l0 and 0x03) shl 4) or (l1 ushr 4))] shl 24) or
                  (SBOX3[sboxbit(((l1 and 0x0F) shl 2) or (l2 ushr 6))] shl 20) or
                  (SBOX4[sboxbit(l2 and 0x3F)] shl 16) or
                  (SBOX5[sboxbit(l3 ushr 2)] shl 12) or
                  (SBOX6[sboxbit(((l3 and 0x03) shl 4) or (l4 ushr 4))] shl 8) or
                  (SBOX7[sboxbit(((l4 and 0x0F) shl 2) or (l5 ushr 6))] shl 4) or
                  SBOX8[sboxbit(l5 and 0x3F)]

        res = bitnumIntL(res, 15, 0) or bitnumIntL(res, 6, 1) or bitnumIntL(res, 19, 2) or
              bitnumIntL(res, 20, 3) or bitnumIntL(res, 28, 4) or bitnumIntL(res, 11, 5) or
              bitnumIntL(res, 27, 6) or bitnumIntL(res, 16, 7) or bitnumIntL(res, 0, 8) or
              bitnumIntL(res, 14, 9) or bitnumIntL(res, 22, 10) or bitnumIntL(res, 25, 11) or
              bitnumIntL(res, 4, 12) or bitnumIntL(res, 17, 13) or bitnumIntL(res, 30, 14) or
              bitnumIntL(res, 9, 15) or bitnumIntL(res, 1, 16) or bitnumIntL(res, 7, 17) or
              bitnumIntL(res, 23, 18) or bitnumIntL(res, 13, 19) or bitnumIntL(res, 31, 20) or
              bitnumIntL(res, 26, 21) or bitnumIntL(res, 2, 22) or bitnumIntL(res, 8, 23) or
              bitnumIntL(res, 18, 24) or bitnumIntL(res, 12, 25) or bitnumIntL(res, 29, 26) or
              bitnumIntL(res, 5, 27) or bitnumIntL(res, 21, 28) or bitnumIntL(res, 10, 29) or
              bitnumIntL(res, 3, 30) or bitnumIntL(res, 24, 31)

        return res
    }

    private fun desKeySetup(key: ByteArray, offset: Int, modeDecrypt: Boolean): Array<ByteArray> {
        val keySlice = ByteArray(8)
        System.arraycopy(key, offset, keySlice, 0, 8)

        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitnum(keySlice, KEY_PERM_C[i], 31 - i)
            d = d or bitnum(keySlice, KEY_PERM_D[i], 31 - i)
        }

        val schedule = Array(16) { ByteArray(6) }
        for (i in 0 until 16) {
            val shift = KEY_RND_SHIFT[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xFFFFFFF0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xFFFFFFF0.toInt()

            val toGen = if (modeDecrypt) 15 - i else i
            for (j in 0 until 24) {
                val bytePos = j / 8
                val bitVal = bitnumIntR(c, KEY_COMPRESSION[j], 7 - (j % 8))
                schedule[toGen][bytePos] = (schedule[toGen][bytePos].toInt() or bitVal).toByte()
            }
            for (j in 24 until 48) {
                val bytePos = j / 8
                val bitVal = bitnumIntR(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
                schedule[toGen][bytePos] = (schedule[toGen][bytePos].toInt() or bitVal).toByte()
            }
        }
        return schedule
    }

    private fun desCrypt(inp: ByteArray, out: ByteArray, schedule: Array<ByteArray>) {
        val st = ip(inp)
        var s0 = st[0]
        var s1 = st[1]

        for (idx in 0 until 15) {
            val t = s1
            s1 = f(s1, schedule[idx]) xor s0
            s0 = t
        }
        s0 = f(s1, schedule[15]) xor s0

        invIp(s0, s1, out)
    }

    private class TripleDesSchedule(
        val sched0: Array<ByteArray>,
        val sched1: Array<ByteArray>,
        val sched2: Array<ByteArray>
    )

    private fun threeDesKeySetup(key: ByteArray, modeDecrypt: Boolean): TripleDesSchedule {
        return if (!modeDecrypt) {
            TripleDesSchedule(
                sched0 = desKeySetup(key, 0, false),
                sched1 = desKeySetup(key, 8, true),
                sched2 = desKeySetup(key, 16, false)
            )
        } else {
            TripleDesSchedule(
                sched0 = desKeySetup(key, 16, true),
                sched1 = desKeySetup(key, 8, false),
                sched2 = desKeySetup(key, 0, true)
            )
        }
    }

    private fun threeDesCryptBlock(inp: ByteArray, out: ByteArray, sched: TripleDesSchedule) {
        val tmp1 = ByteArray(8)
        val tmp2 = ByteArray(8)
        desCrypt(inp, tmp1, sched.sched0)
        desCrypt(tmp1, tmp2, sched.sched1)
        desCrypt(tmp2, out, sched.sched2)
    }

    /**
     * Decrypts hex-encoded QQ Music QRC (EQRC) string into UTF-8 decompressed string.
     */
    fun decryptQrcHex(hexString: String): String? {
        if (hexString.isBlank() || hexString.length % 2 != 0) return null
        val raw = hexToBytes(hexString) ?: return null
        if (raw.isEmpty()) return null

        val sched = threeDesKeySetup(DEC_KEY, true)
        val dec = ByteArray(raw.size)

        val fullBlocks = (raw.size / 8) * 8
        val blockIn = ByteArray(8)
        val blockOut = ByteArray(8)

        for (i in 0 until fullBlocks step 8) {
            System.arraycopy(raw, i, blockIn, 0, 8)
            threeDesCryptBlock(blockIn, blockOut, sched)
            System.arraycopy(blockOut, 0, dec, i, 8)
        }

        return try {
            val inflater = Inflater()
            inflater.setInput(dec)
            val buffer = ByteArray(4096)
            val baos = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                baos.write(buffer, 0, count)
            }
            inflater.end()
            baos.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(hex[i], 16)
            val low = Character.digit(hex[i + 1], 16)
            if (high == -1 || low == -1) return null
            out[i / 2] = ((high shl 4) or low).toByte()
            i += 2
        }
        return out
    }
}
