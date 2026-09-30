package com.example.relay.pcgateway.official

import kotlinx.serialization.Serializable

/**
 * One entry of a JMA XML Atom feed (http://www.data.jma.go.jp/developer/xml/feed/).
 *
 * The feed lists published JMA XML telegrams; each entry links to the full telegram. The feed
 * itself is unsigned, so parsing it yields structure only - provenance stays at transport level.
 */
@Serializable
data class JmaFeedEntry(
    val title: String,
    val id: String,
    val updated: String,
    /** Publishing office, e.g. "気象庁本庁" (Atom author/name). */
    val author: String?,
    /** Link to the full JMA XML telegram; null when the entry carries no usable link. */
    val link: String?,
    /** Feed-level headline excerpt (Atom content), often the telegram headline. */
    val content: String?,
)

@Serializable
data class JmaFeed(
    val title: String,
    val updated: String,
    val entries: List<JmaFeedEntry>,
)

/**
 * Fail-closed parser for JMA's Atom 1.0 feeds over [HardenedXml].
 *
 * Requires the Atom namespace root <feed> with title/updated, and title/id/updated on every
 * entry; anything else raises [OfficialXmlParseException] instead of degrading silently.
 */
object JmaAtomFeedParser {
    const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"

    fun parse(xml: String): JmaFeed {
        val root = HardenedXml.parse(xml).documentElement
            ?: throw OfficialXmlParseException("empty Atom document")
        if (root.namespaceURI != ATOM_NAMESPACE || root.localName != "feed") {
            throw OfficialXmlParseException(
                "not an Atom feed: {${root.namespaceURI}}${root.localName}"
            )
        }
        val entries = HardenedXml.childElements(root, ATOM_NAMESPACE, "entry").map { entry ->
            val author = HardenedXml.childElements(entry, ATOM_NAMESPACE, "author").firstOrNull()
            JmaFeedEntry(
                title = HardenedXml.requiredText(entry, ATOM_NAMESPACE, "title"),
                id = HardenedXml.requiredText(entry, ATOM_NAMESPACE, "id"),
                updated = HardenedXml.requiredText(entry, ATOM_NAMESPACE, "updated"),
                author = author?.let { HardenedXml.optionalText(it, ATOM_NAMESPACE, "name") },
                link = HardenedXml.childElements(entry, ATOM_NAMESPACE, "link")
                    .firstOrNull()?.getAttribute("href")?.ifBlank { null },
                content = HardenedXml.optionalText(entry, ATOM_NAMESPACE, "content"),
            )
        }
        return JmaFeed(
            title = HardenedXml.requiredText(root, ATOM_NAMESPACE, "title"),
            updated = HardenedXml.requiredText(root, ATOM_NAMESPACE, "updated"),
            entries = entries,
        )
    }
}
