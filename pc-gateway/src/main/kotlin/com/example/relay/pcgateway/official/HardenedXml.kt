package com.example.relay.pcgateway.official

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Thrown when an official-information XML document is malformed, unsafe, or incomplete. */
class OfficialXmlParseException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Parses untrusted official-information XML with entity expansion fully disabled.
 *
 * JMA Atom feeds and CAP alerts arrive over the network from outside the trust boundary, so
 * every parse here is fail-closed against XXE: DOCTYPE declarations are rejected outright,
 * external general/parameter entities and external DTD loading are off, XInclude is off, and
 * entity references are never expanded. A document that needs any of those is refused rather
 * than partially processed.
 */
internal object HardenedXml {
    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        // Fail closed instead of resolving anything the features above did not already block.
        builder.setEntityResolver { _, _ ->
            throw OfficialXmlParseException("external entity resolution is not allowed")
        }
        return try {
            builder.parse(InputSource(xml.reader()))
        } catch (rejected: OfficialXmlParseException) {
            throw rejected
        } catch (cause: Exception) {
            throw OfficialXmlParseException("rejected official XML: ${cause.message}", cause)
        }
    }

    fun childElements(parent: Element, namespaceUri: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == namespaceUri && node.localName == localName) {
                result.add(node)
            }
            node = node.nextSibling
        }
        return result
    }

    fun requiredText(parent: Element, namespaceUri: String, localName: String): String {
        val value = optionalText(parent, namespaceUri, localName)
        if (value.isNullOrBlank()) {
            throw OfficialXmlParseException("missing required element <$localName>")
        }
        return value
    }

    fun optionalText(parent: Element, namespaceUri: String, localName: String): String? =
        childElements(parent, namespaceUri, localName).firstOrNull()?.textContent?.trim()
}
