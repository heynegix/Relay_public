package com.example.relay.pcgateway.rescue

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows DPAPI (crypt32!CryptProtectData) via a direct JNA mapping.
 *
 * Per-user scope: the protected blob can only be opened by the same Windows user on the same
 * machine (or a roaming profile), which is exactly the shelter-PC boundary. The native library
 * is loaded lazily so non-Windows platforms never touch it. This module deliberately does not
 * claim HSM/TPM/KMS protection: DPAPI keys live with the user profile.
 */
internal object WindowsDpapi {
    val isSupported: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    @Structure.FieldOrder("cbData", "pbData")
    internal class DataBlob() : Structure() {
        @JvmField var cbData: Int = 0

        @JvmField var pbData: Pointer? = null

        constructor(bytes: ByteArray) : this() {
            val memory = Memory(maxOf(bytes.size, 1).toLong())
            memory.write(0, bytes, 0, bytes.size)
            pbData = memory
            cbData = bytes.size
        }

        fun toByteArray(): ByteArray = pbData?.getByteArray(0, cbData) ?: ByteArray(0)
    }

    @Suppress("FunctionNaming", "LongParameterList")
    private interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(
            pDataIn: DataBlob,
            szDataDescr: String?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            pDataIn: DataBlob,
            ppszDataDescr: Pointer?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean

        companion object {
            val INSTANCE: Crypt32 by lazy {
                Native.load("Crypt32", Crypt32::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }

    @Suppress("FunctionNaming")
    private interface Kernel32 : StdCallLibrary {
        fun LocalFree(hMem: Pointer?): Pointer?

        companion object {
            val INSTANCE: Kernel32 by lazy {
                Native.load("Kernel32", Kernel32::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }

    fun protect(plain: ByteArray, entropy: ByteArray): ByteArray = call(plain, entropy, protecting = true)

    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray = call(blob, entropy, protecting = false)

    private fun call(input: ByteArray, entropy: ByteArray, protecting: Boolean): ByteArray {
        check(isSupported) { "Windows DPAPI is unavailable on this platform" }
        val dataIn = DataBlob(input)
        val entropyBlob = DataBlob(entropy)
        val dataOut = DataBlob()
        val ok = if (protecting) {
            Crypt32.INSTANCE.CryptProtectData(
                dataIn, null, entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, dataOut,
            )
        } else {
            Crypt32.INSTANCE.CryptUnprotectData(
                dataIn, null, entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, dataOut,
            )
        }
        check(ok) {
            if (protecting) {
                "DPAPI protection failed"
            } else {
                "DPAPI unprotection failed (different Windows user/machine, wrong entropy, or tampered data)"
            }
        }
        return try {
            dataOut.toByteArray()
        } finally {
            Kernel32.INSTANCE.LocalFree(dataOut.pbData)
        }
    }
}
