package com.metaplex.umi

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.metaplex.base58.decodeBase58
import com.metaplex.base58.encodeToBase58String
import com.metaplex.umi_public_keys.PublicKey
import diglol.crypto.Ed25519

val DEFAULT_SIGNATURE = ByteArray(0)

/**
 * The maximum amount of bytes that can be used for a transaction.
 * @category Transactions
 */
const val PACKET_DATA_SIZE = 1280 - 40 - 8

const val SIGNATURE_LENGTH = 64

/**
 * Defines an account required by an instruction.
 * It includes its public key, whether it is signing the
 * transaction and whether the account should be writable.
 *
 * @category Transactions
 */
data class AccountMeta(
    var publicKey: PublicKey,
    var isSigner: Boolean,
    var isWritable: Boolean
) {
    override fun toString(): String {
        return "pubkey:${publicKey.toBase58()}, signer:$isSigner, writable:$isWritable"
    }
}

class SerializeConfig(
    val requireAllSignatures: Boolean = true,
    val verifySignatures: Boolean = true
)

data class TransactionInstruction(
    var programId: PublicKey,
    var keys: List<AccountMeta>,
    var data: ByteArray = DEFAULT_SIGNATURE
)

data class SignaturePubkeyPair(
    var signature: ByteArray?,
    val publicKey: PublicKey
)

class NonceInformation(
    val nonce: String,
    val nonceInstruction: TransactionInstruction
)

class Transaction {
    var signatures = mutableListOf<SignaturePubkeyPair>()
    val signature: ByteArray?
        get() = signatures.firstOrNull()?.signature

    private lateinit var serializedMessage: ByteArray
    var feePayer: PublicKey? = null

    val instructions = mutableListOf<TransactionInstruction>()
    lateinit var recentBlockhash: String
    var nonceInfo: NonceInformation? = null

    fun addInstruction(vararg instruction: TransactionInstruction) = add(*instruction)

    fun add(vararg instruction: TransactionInstruction): Transaction {
        require(instruction.isNotEmpty()) { "No instructions" }
        this.instructions.addAll(instruction)
        return this
    }

    fun setRecentBlockHash(recentBlockhash: String) {
        this.recentBlockhash = recentBlockhash
    }

    suspend fun sign(vararg signer: Signer) {
        sign(signer.toList())
    }

    suspend fun sign(signers: List<Signer>) {
        require(signers.isNotEmpty()) { "No signers" }

        // Dedupe signers
        val seen = mutableSetOf<String>()
        val uniqueSigners = mutableListOf<Signer>()
        for (signer in signers) {
            val key = signer.publicKey.toString()
            if (seen.contains(key)) {
                continue
            } else {
                seen.add(key)
                uniqueSigners.add(signer)
            }
        }

        uniqueSigners.map {
            SignaturePubkeyPair(
                signature = null,
                publicKey = it.publicKey
            )
        }.let {
            this.signatures.addAll(it)
        }

        val message = compile()
        partialSign(message, uniqueSigners)
        verifySignatures(message.serialize(), true)
    }

    suspend fun partialSign(vararg signers: Signer) {
        require(signers.isNotEmpty()) { "No signers" }

        // Dedupe signers
        val seen = mutableSetOf<String>()
        val uniqueSigners = mutableListOf<Signer>()
        for (signer in signers) {
            val key = signer.publicKey.toString()
            if (seen.contains(key)) {
                continue
            } else {
                seen.add(key)
                uniqueSigners.add(signer)
            }
        }

        val message = compile()
        partialSign(message, uniqueSigners)
    }

    private suspend fun partialSign(message: Message, signers: List<Signer>) {
        val signData = message.serialize()
        signers.forEach { signer ->
            val signature = signer.signMessage(signData)
            _addSignature(signer.publicKey, signature)
        }
    }

    fun addSignature(pubkey: PublicKey, signature: ByteArray) {
        compile() // Ensure signatures array is populated
        _addSignature(pubkey, signature)
    }

    private fun _addSignature(pubkey: PublicKey, signature: ByteArray) {
        require(signature.count() == 64)

        val index = this.signatures.indexOfFirst { sigpair ->
            pubkey.equals(sigpair.publicKey)
        }
        if (index < 0) {
            throw Error("unknown signer: $pubkey")
        }

        this.signatures[index].signature = signature
    }

    suspend fun verifySignatures(): Boolean {
        return verifySignatures(this.serializeMessage(), true)
    }

    private suspend fun verifySignatures(signData: ByteArray, requireAllSignatures: Boolean): Boolean {
        this.signatures.forEach { (signature, publicKey) ->
            if (signature === null) {
                if (requireAllSignatures) {
                    return false
                }
            } else {
                if (
                    !Ed25519.verify(signature, publicKey.pubkey, signData)
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun compile(): Message {
        val message = compileMessage()
        val signedKeys = message.accountKeys.slice(
            0 until message.header.numRequiredSignatures
        )

        if (this.signatures.count() == signedKeys.count()) {
            var valid = true
            this.signatures.forEachIndexed { index, pair ->
                if (!signedKeys[index].equals(pair.publicKey)) {
                    valid = false
                    return@forEachIndexed
                }
            }
            if (valid) return message
        }

        this.signatures = signedKeys.map { publicKey ->
            SignaturePubkeyPair(
                signature = null,
                publicKey = publicKey
            )
        }.toMutableList()

        return message
    }

    fun compileMessage(): Message {
        this.nonceInfo?.let { nonceInfo ->
            if (instructions.first() != nonceInfo.nonceInstruction) {
                recentBlockhash = nonceInfo.nonce
                instructions.add(0, nonceInfo.nonceInstruction)
            }
        }
        require(recentBlockhash.isNotEmpty()) { "Transaction recentBlockhash required" }

        if (instructions.isEmpty()) {
            print("No instructions provided")
        }

        val feePayer = feePayer ?: signatures.firstOrNull()?.publicKey
        requireNotNull(feePayer) { "Transaction fee payer required" }

        val programIds = mutableSetOf<PublicKey>()
        val accountMetas = mutableListOf<AccountMeta>()
        for (instruction in instructions) {
            for (accountMeta in instruction.keys) {
                accountMetas.add(accountMeta)
            }
            programIds.add(instruction.programId)
        }

        // Append programID account metas
        for (programId in programIds) {
            accountMetas.add(
                AccountMeta(
                    publicKey = programId,
                    isSigner = false,
                    isWritable = false
                )
            )
        }

        // Cull duplicate account metas
        val uniqueMetas = mutableListOf<AccountMeta>()
        for (accountMeta in accountMetas) {
            val pubkeyString = accountMeta.publicKey.toBase58()
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.toBase58() == pubkeyString }
            if (uniqueIndex > -1) {
                uniqueMetas[uniqueIndex].isWritable =
                    uniqueMetas[uniqueIndex].isWritable || accountMeta.isWritable
            } else {
                uniqueMetas.add(accountMeta)
            }
        }

        // Sort. Prioritizing first by signer, then by writable
        uniqueMetas.sortWith { x, y ->
            if (x.isSigner != y.isSigner) {
                // Signers always come before non-signers
                return@sortWith if (x.isSigner) -1 else  1
            }
            if (x.isWritable != y.isWritable) {
                // Writable accounts always come before read-only accounts
                return@sortWith if (x.isWritable) -1 else 1
            }
            // Otherwise, sort by pubkey, stringwise.
            return@sortWith x.publicKey.toBase58().compareTo(y.publicKey.toBase58())
        }

        // Move fee payer to the front
        val feePayerIndex = uniqueMetas.indexOfFirst { it.publicKey.equals(feePayer) }
        if (feePayerIndex > -1) {
            val payerMeta = uniqueMetas.removeAt(feePayerIndex)
            payerMeta.isSigner = true
            payerMeta.isWritable = true
            uniqueMetas.add(0, payerMeta)
        } else {
            uniqueMetas.add(
                index = 0,
                element = AccountMeta(
                    publicKey = feePayer,
                    isSigner = true,
                    isWritable = true
                )
            )
        }

        // Disallow unknown signers
        for (signature in signatures) {
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.equals(signature.publicKey) }
            if (uniqueIndex > -1) {
                if (!uniqueMetas[uniqueIndex].isSigner) {
                    uniqueMetas[uniqueIndex].isSigner = true
                    print(
                        "Transaction references a signature that is unnecessary, " +
                                "only the fee payer and instruction signer accounts should sign a transaction. " +
                                "This behavior is deprecated and will throw an error in the next major version release"
                    )
                }
            } else {
                throw Error("unknown signer: ${signature.publicKey}")
            }
        }

        var numRequiredSignatures = 0
        var numReadonlySignedAccounts = 0
        var numReadonlyUnsignedAccounts = 0

        // Split out signing from non-signing keys and count header values
        val signedKeys = mutableListOf<PublicKey>()
        val unsignedKeys = mutableListOf<PublicKey>()
        uniqueMetas.forEach {
            if (it.isSigner) {
                signedKeys.add(it.publicKey)
                numRequiredSignatures += 1
                if (!it.isWritable) {
                    numReadonlySignedAccounts += 1
                }
            } else {
                unsignedKeys.add(it.publicKey)
                if (!it.isWritable) {
                    numReadonlyUnsignedAccounts += 1
                }
            }
        }

        val accountKeys = signedKeys.plus(unsignedKeys)
        val instructions: List<CompiledInstruction> = instructions.map { instruction ->
            val (programId, _, data) = instruction
            CompiledInstruction(
                programIdIndex = accountKeys.indexOf(programId),
                accounts = instruction.keys.map { meta ->
                    accountKeys.indexOf(meta.publicKey)
                },
                data = data.encodeToBase58String()
            )
        }

        for (instruction in instructions) {
            require(instruction.programIdIndex >= 0)
            instruction.accounts.forEach { keyIndex -> require(keyIndex >= 0) }
        }

        return Message(
            header = MessageHeader().apply {
                this.numRequiredSignatures = numRequiredSignatures.toByte()
                this.numReadonlySignedAccounts = numReadonlySignedAccounts.toByte()
                this.numReadonlyUnsignedAccounts = numReadonlyUnsignedAccounts.toByte()
            },
            accountKeys = accountKeys,
            recentBlockhash = recentBlockhash,
            instructions = instructions
        )

    }

    /**
     * Get a buffer of the Transaction data that need to be covered by signatures
     */
    fun serializeMessage(): ByteArray {
        return compile().serialize()
    }

    /**
     * Serialize the Transaction in the wire format.
     */
    suspend fun serialize(config: SerializeConfig = SerializeConfig()): ByteArray {
        val signData = this.serializeMessage()
        if (config.verifySignatures &&
            !this.verifySignatures(signData, config.requireAllSignatures)
        ) {
            throw Error("Signature verification failed")
        }

        return this.serialize(signData)
    }

    internal fun serialize(signData: ByteArray): ByteArray {
        val signatureCount = Shortvec.encodeLength(signatures.count())
        val transactionLength = signatureCount.count() + signatures.count() * 64 + signData.count()
        val wireTransaction = PlatformBuffer.allocate(transactionLength)
        require(signatures.count() < 256)
        wireTransaction.writeBytes(signatureCount)
        signatures.forEach { (signature, _) ->
            when {
                signature !== null -> {
                    require(signature.count() == 64) { "signature has invalid length" }
                    wireTransaction.writeBytes(signature)
                }
                else -> {
                    wireTransaction.writeBytes(ByteArray(SIGNATURE_LENGTH))
                }
            }
        }
        wireTransaction.writeBytes(signData)
        wireTransaction.resetForRead()
        val out = wireTransaction.readByteArray(transactionLength)
        require(out.count() <= PACKET_DATA_SIZE) {
            "Transaction too large: ${out.count()} > $PACKET_DATA_SIZE"
        }
        return out
    }

    companion object {

        fun from(buffer: ByteArray): Transaction {
            // Slice up wire data
            var byteArray = buffer

            val signatureCount = Shortvec.decodeLength(byteArray)
            byteArray = signatureCount.second
            val signatures = mutableListOf<String>()
            for (i in 0 until signatureCount.first) {
                val signature = byteArray.slice(0 until SIGNATURE_LENGTH)
                byteArray = byteArray.drop(SIGNATURE_LENGTH).toByteArray()
                signatures.add(signature.toByteArray().encodeToBase58String())
            }

            return populate(Message.from(byteArray), signatures)
        }

        fun populate(message: Message, signatures: List<String> = emptyList()): Transaction {
            val transaction = Transaction()
            transaction.recentBlockhash = message.recentBlockhash
            if (message.header.numRequiredSignatures > 0) {
                transaction.feePayer = message.accountKeys[0]
            }
            signatures.forEachIndexed { index, signature ->
                transaction.signatures.add(
                    SignaturePubkeyPair(
                        signature = if (signature == DEFAULT_SIGNATURE.encodeToBase58String()) {
                            null
                        } else {
                            signature.decodeBase58()
                        },
                        publicKey = message.accountKeys[index]
                    )
                )
            }

            message.instructions.forEach { instruction ->
                val keys = instruction.accounts.map { account ->
                    val pubkey = message.accountKeys[account]
                    return@map AccountMeta(
                        publicKey = pubkey,
                        isSigner = transaction.signatures.any { keyObj ->
                            keyObj.publicKey.toString() === pubkey.toString()
                        } || message.isAccountSigner(account),
                        isWritable = message.isAccountWritable(account)
                    )
                }

                transaction.instructions.add(
                    TransactionInstruction(
                        keys = keys,
                        programId = message.accountKeys[instruction.programIdIndex],
                        data = instruction.data.decodeBase58()
                    )
                )
            }
            return transaction
        }
    }
}