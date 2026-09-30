@file:Suppress("MaxLineLength")
package com.example.relay.pcgateway

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RegionalDeploymentProfileTest {
    @Test fun missingProfileIsNeutral() {
        val p = RegionalDeploymentProfileLoader.load(java.nio.file.Path.of("does-not-exist.json"))
        assertEquals("global", p.regionId)
        assertEquals(false, p.map.enabled)
    }
    @Test fun internationalProfileSupportsTimezoneAndAntimeridian() {
        val f = Files.createTempFile("regional-profile-", ".json")
        Files.writeString(f, """{"regionId":"example-region","displayName":"Example Region","countryCode":"XX","timezoneId":"Etc/UTC","defaultLocale":"en","supportedLocales":["en"],"map":{"enabled":true,"initialLatitude":0.0,"initialLongitude":180.0,"initialZoom":10,"south":-1.0,"north":1.0,"west":179.0,"east":-179.0,"minZoom":8,"maxNativeZoom":10,"maxZoom":14,"tileTemplate":"https://tiles.example.invalid/{z}/{x}/{y}.png","attribution":"Example map provider"},"officialInfo":{"enabled":true,"providerName":"Example authority","endpoint":"https://alerts.example.invalid/feed","format":"CAP_1_2","sources":[{"title":"Example authority","organization":"Example authority","url":"https://authority.example.invalid/alerts"}]}}""")
        val p = RegionalDeploymentProfileLoader.load(f)
        assertEquals("Etc/UTC", p.timezoneId)
        assertEquals(179.0, p.map.west, 0.0)
        assertEquals(-179.0, p.map.east, 0.0)
        Files.deleteIfExists(f)
    }
    @Test fun invalidExistingProfileFailsClosed() {
        val f = Files.createTempFile("regional-profile-invalid-", ".json")
        Files.writeString(f, """{"regionId":"INVALID","displayName":"","timezoneId":"bad"}""")
        assertThrows(IllegalArgumentException::class.java) { RegionalDeploymentProfileLoader.load(f) }
        Files.deleteIfExists(f)
    }
}
