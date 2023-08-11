package com.metaplex.umi

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.metaplex.base58.decodeBase58
import com.metaplex.base58.encodeToBase58String
import com.metaplex.umi_public_keys.PUBLIC_KEY_LENGTH
import com.metaplex.umi_public_keys.PublicKey

class MessageHeader {
    var numRequiredSignatures: Byte = 0
    var numReadonlySignedAccounts: Byte = 0
    var numReadonlyUnsignedAccounts: Byte = 0

    fun toByteArray(): ByteArray {
        return byteArrayOf(
            numRequiredSignatures,
            numReadonlySignedAccounts,
            numReadonlyUnsignedAccounts
        )
    }

    companion object {
        const val HEADER_LENGTH = 3
    }
}

data class CompiledInstruction(
    val programIdIndex: Int,
    val accounts: List<Int>,
    val data: String
)

class WrappedInstruction(
    var programIdIndex: Byte = 0,
    var keyIndicesCount: ByteArray,
    var keyIndices: ByteArray,
    var dataLength: ByteArray,
    var data: ByteArray,
) {
    // 1 = programIdIndex length
    val length: Int
        get() =// 1 = programIdIndex length
            1 + keyIndicesCount.count() + keyIndices.count() + dataLength.count() + data.count()
}


class Message(
    val header: MessageHeader,
    val accountKeys: List<PublicKey>,
    val recentBlockhash: String,
    val instructions: List<CompiledInstruction>,
    private var feePayer: PublicKey? = null,
    private var indexToProgramIds: MutableMap<Int, PublicKey> = mutableMapOf()
) {

    init {
        this.instructions.forEach { ix ->
            this.indexToProgramIds[ix.programIdIndex] = this.accountKeys[ix.programIdIndex]
        }
    }
    fun isAccountSigner(index: Int): Boolean {
        return index < this.header.numRequiredSignatures
    }

    fun isAccountWritable(index: Int): Boolean {
        return index < header.numRequiredSignatures - header.numReadonlySignedAccounts ||
                (index >= header.numRequiredSignatures &&
                        index < accountKeys.count() - header.numReadonlyUnsignedAccounts)
    }

    fun isProgramId(index: Int): Boolean {
        return indexToProgramIds.containsKey(index)
    }

    fun programIds(): List<PublicKey> {
        return indexToProgramIds.values.toList()
    }

    fun nonProgramIds(): List<PublicKey> {
        return this.accountKeys.filterIndexed { index, _ -> !this.isProgramId(index) }
    }

    fun serialize(): ByteArray {
        require(recentBlockhash.isNotEmpty()) { "recentBlockhash required" }
        require(instructions.isNotEmpty()) { "No instructions provided" }
        val numKeys = this.accountKeys.count()
        val keyCount = Shortvec.encodeLength(numKeys)
        val instructions = this.instructions.map { instruction ->
            val (programIdIndex, accounts, _) = instruction
            val data = instruction.data.decodeBase58()

            val keyIndicesCount = Shortvec.encodeLength(accounts.count())
            val dataCount = Shortvec.encodeLength(data.count())

            WrappedInstruction(
                programIdIndex = programIdIndex.toByte(),
                keyIndicesCount = keyIndicesCount,
                keyIndices = accounts.map{ it.toByte() }.toByteArray(),
                dataLength = dataCount,
                data = data,
            )
        }

        val compiledInstructionsLength = instructions.sumOf { it.length }
        val instructionCount = Shortvec.encodeLength(instructions.size)
        val bufferSize = (MessageHeader.HEADER_LENGTH + RECENT_BLOCK_HASH_LENGTH + keyCount.size
                + numKeys * PublicKey.PUBLIC_KEY_LENGTH + instructionCount.size
                + compiledInstructionsLength)

        val buffer = PlatformBuffer.allocate(size = bufferSize)
        buffer.writeByte(header.numRequiredSignatures)
        buffer.writeByte(header.numReadonlySignedAccounts)
        buffer.writeByte(header.numReadonlyUnsignedAccounts)
        buffer.writeBytes(keyCount)
        for (accountKey in accountKeys) {
            buffer.writeBytes(accountKey.pubkey)
        }
        buffer.writeBytes(recentBlockhash.decodeBase58())
        buffer.writeBytes(instructionCount)
        for (instruction in instructions) {
            buffer.writeByte(instruction.programIdIndex)
            buffer.writeBytes(instruction.keyIndicesCount)
            buffer.writeBytes(instruction.keyIndices)
            buffer.writeBytes(instruction.dataLength)
            buffer.writeBytes(instruction.data)
        }
        buffer.resetForRead()
        return buffer.readByteArray(bufferSize)
    }

    fun setFeePayer(publicKey: PublicKey) {
        this.feePayer = publicKey
    }

    companion object {

        fun from(buffer: ByteArray): Message {
            // Slice up wire data
            var byteArray = buffer

            val numRequiredSignatures = byteArray.first().toInt().also { byteArray = byteArray.drop(1).toByteArray() }
            val numReadonlySignedAccounts = byteArray.first().toInt().also { byteArray = byteArray.drop(1).toByteArray() }
            val numReadonlyUnsignedAccounts = byteArray.first().toInt().also { byteArray = byteArray.drop(1).toByteArray() }

            val accountCount = Shortvec.decodeLength(byteArray)
            byteArray = accountCount.second
            val accountKeys = mutableListOf<String>()
            for (i in 0 until accountCount.first) {
                val account = byteArray.slice(0 until PUBLIC_KEY_LENGTH)
                byteArray = byteArray.drop(PUBLIC_KEY_LENGTH).toByteArray()
                accountKeys.add(account.toByteArray().encodeToBase58String())
            }

            val recentBlockhash = byteArray.slice(0 until PUBLIC_KEY_LENGTH).toByteArray()
            byteArray = byteArray.drop(PUBLIC_KEY_LENGTH).toByteArray()

            val instructionCount = Shortvec.decodeLength(byteArray)
            byteArray = instructionCount.second
            val instructions = mutableListOf<CompiledInstruction>()
            for (i in 0 until instructionCount.first) {
                val programIdIndex = byteArray.first().toInt().also { byteArray = byteArray.drop(1).toByteArray() }
                val accountCount = Shortvec.decodeLength(byteArray)
                byteArray = accountCount.second
                val accounts =
                    byteArray.slice(0 until accountCount.first).toByteArray().toList().map(Byte::toInt)
                byteArray = byteArray.drop(accountCount.first).toByteArray()
                val dataLength = Shortvec.decodeLength(byteArray)
                byteArray = dataLength.second
                val dataSlice = byteArray.slice(0 until dataLength.first).toByteArray()
                val data = dataSlice.encodeToBase58String()
                byteArray = byteArray.drop(dataLength.first).toByteArray()
                instructions.add(
                    CompiledInstruction(
                        programIdIndex = programIdIndex,
                        accounts = accounts,
                        data = data,
                    )
                )
            }

            return Message(
                header = MessageHeader().apply {
                    this.numRequiredSignatures = numRequiredSignatures.toByte()
                    this.numReadonlySignedAccounts = numReadonlySignedAccounts.toByte()
                    this.numReadonlyUnsignedAccounts = numReadonlyUnsignedAccounts.toByte()
                },
                accountKeys = accountKeys.map { PublicKey(it) },
                recentBlockhash = String().plus(recentBlockhash.encodeToBase58String()),
                instructions = instructions
            )
        }

        private const val RECENT_BLOCK_HASH_LENGTH = 32
    }
}
