package dev.goutou.wingman.wechat

import java.security.MessageDigest

/** Length-prefixed fields prevent ambiguous keys; digests keep content out of cache identifiers. */
internal fun conversationDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun conversationRequestKey(name: String, settings: String, context: String?, msgs: List<ChatMsg>): String =
    conversationDigest(name, settings, context.orEmpty(), *msgs.map {
        conversationDigest(it.fromMe.toString(), it.who, it.text, it.attachment.toString())
    }.toTypedArray())

internal fun roleObservationKey(name: String, fromMe: Boolean, text: String): String =
    conversationDigest(name, fromMe.toString(), text)
