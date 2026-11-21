package com.contextshield

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale

data class ModelAssessment(
    val label: String,
    val confidence: Float,
    val probabilities: List<Float>,
    val tactics: Map<String, Float>
)

/** Local-only INT8 classifier. Inference receives one in-memory notification and makes no network calls. */
object OnDeviceModel {
    private const val ASSET_MODEL = "model/contextshield-risk-int8.onnx"
    private const val ASSET_VOCAB = "model/vocab.txt"
    private const val ASSET_META = "model/model_metadata.json"
    private const val MODEL_FILE = "contextshield-risk-int8.onnx"
    private const val MAX_LENGTH = 128
    private val labels = listOf("safe", "needs_check", "suspicious", "dangerous")
    private val tactics = listOf(
        "urgency", "impersonation", "phishing_link", "payment_request", "credential_theft",
        "verification_needed", "investment_or_prize", "remote_access", "secrecy", "job_scam",
        "marketplace_scam", "third_party", "too_good_to_be_true"
    )

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    @Volatile private var loadFailure: String? = null

    fun isReady(context: Context): Boolean = runCatching { ensureLoaded(context) }.isSuccess
    fun status(): String = loadFailure ?: if (session != null) "ONNX INT8 · локально" else "модель ещё не загружена"

    fun assess(context: Context, text: String): ModelAssessment? = runCatching {
        ensureLoaded(context)
        val currentSession = checkNotNull(session)
        val input = checkNotNull(tokenizer).encode(text)
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, MAX_LENGTH.toLong())
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(input.ids), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(input.mask), shape)
        val typesTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(input.types), shape)
        try {
            currentSession.run(mapOf(
                "input_ids" to idsTensor,
                "attention_mask" to maskTensor,
                "token_type_ids" to typesTensor
            )).use { result ->
                val riskLogits = (result[0].value as Array<*>)[0] as FloatArray
                val tacticLogits = (result[1].value as Array<*>)[0] as FloatArray
                require(riskLogits.size == labels.size && tacticLogits.size == tactics.size) { "Неожиданное число выходов модели" }
                val max = riskLogits.maxOrNull() ?: 0f
                val exp = riskLogits.map { kotlin.math.exp((it - max).toDouble()).toFloat() }
                val sum = exp.sum().coerceAtLeast(1e-9f)
                val probabilities = exp.map { it / sum }
                val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                val tacticProbabilities = tacticLogits.map { value ->
                    (1.0 / (1.0 + kotlin.math.exp(-value.toDouble()))).toFloat()
                }
                ModelAssessment(labels[best], probabilities[best], probabilities,
                    tactics.indices.associate { tactics[it] to tacticProbabilities[it] })
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
            typesTensor.close()
        }
    }.onFailure { loadFailure = "Локальная модель недоступна: ${it.javaClass.simpleName}" }.getOrNull()

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (session != null && tokenizer != null) return
        val metadata = context.assets.open(ASSET_META).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val labelsFromAsset = metadata.getJSONArray("labels").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        require(labelsFromAsset == labels) { "Схема label-ов модели не совпала с приложением" }
        val tacticsFromAsset = metadata.getJSONArray("tactics").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        require(tacticsFromAsset == tactics) { "Схема тактик модели не совпала с приложением" }
        require(metadata.optInt("max_length", MAX_LENGTH) == MAX_LENGTH) { "Изменилась длина входа модели" }

        val modelFile = File(context.noBackupFilesDir, MODEL_FILE)
        if (!modelFile.isFile) {
            context.assets.open(ASSET_MODEL).use { source ->
                modelFile.outputStream().use { output -> source.copyTo(output) }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(modelFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(digest.equals(metadata.getString("model_sha256"), ignoreCase = true)) {
            "SHA-256 модели не совпал"
        }

        tokenizer = WordPieceTokenizer(context.assets.open(ASSET_VOCAB).bufferedReader(Charsets.UTF_8).useLines { it.toList() })
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
        loadFailure = null
        options.close()
    }

    private data class EncodedInput(val ids: LongArray, val mask: LongArray, val types: LongArray)

    private class WordPieceTokenizer(vocabulary: List<String>) {
        private val ids = vocabulary.withIndex().associate { it.value to it.index.toLong() }
        private val unk = ids["[UNK]"] ?: error("[UNK] не найден в vocab")
        private val pad = ids["[PAD]"] ?: error("[PAD] не найден в vocab")
        private val cls = ids["[CLS]"] ?: error("[CLS] не найден в vocab")
        private val sep = ids["[SEP]"] ?: error("[SEP] не найден в vocab")

        fun encode(text: String): EncodedInput {
            val pieces = basicTokens(text.take(4000)).flatMap(::wordPieces).take(MAX_LENGTH - 2)
            val tokenIds = ArrayList<Long>(MAX_LENGTH)
            tokenIds += cls
            pieces.forEach { tokenIds += ids[it] ?: unk }
            tokenIds += sep
            val used = tokenIds.size
            while (tokenIds.size < MAX_LENGTH) tokenIds += pad
            return EncodedInput(
                ids = tokenIds.toLongArray(),
                mask = LongArray(MAX_LENGTH) { if (it < used) 1L else 0L },
                types = LongArray(MAX_LENGTH)
            )
        }

        private fun basicTokens(text: String): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotEmpty()) {
                    result += current.toString().lowercase(Locale.ROOT)
                    current.setLength(0)
                }
            }
            text.forEach { ch ->
                when {
                    ch.isWhitespace() || Character.isISOControl(ch) -> flush()
                    isPunctuation(ch) -> { flush(); result += ch.toString() }
                    else -> current.append(ch)
                }
            }
            flush()
            return result
        }

        private fun wordPieces(word: String): List<String> {
            if (word.length > 100) return listOf("[UNK]")
            val result = ArrayList<String>()
            var start = 0
            while (start < word.length) {
                var end = word.length
                var found: String? = null
                while (start < end) {
                    val fragment = word.substring(start, end)
                    val candidate = if (start == 0) fragment else "##$fragment"
                    if (candidate in ids) { found = candidate; break }
                    end--
                }
                if (found == null) return listOf("[UNK]")
                result += found
                start = end
            }
            return result
        }

        private fun isPunctuation(ch: Char): Boolean =
            ch in '!'..'/' || ch in ':'..'@' || ch in '['..'`' || ch in '{'..'~' ||
                ch.category == CharCategory.OTHER_PUNCTUATION || ch.category == CharCategory.DASH_PUNCTUATION
    }
}
