package com.firstdj.hdjd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRouterTest {
    private val router = LinkRouter("jain.firstdj.com")

    // ------------------------------------------------------------ internal links
    @Test fun trustedHostStaysInApp() {
        assertEquals(Route.Internal, router.classify("https://jain.firstdj.com/"))
        assertEquals(Route.Internal, router.classify("https://JAIN.firstdj.com/events?id=3#top"))
        assertEquals(Route.Internal, router.classify("https://jain.firstdj.com:443/contact"))
    }

    @Test fun lookalikeHostsAreNotInternal() {
        assertTrue(router.classify("https://jain.firstdj.com.evil.com/") is Route.External)
        assertTrue(router.classify("https://evil.com/jain.firstdj.com") is Route.External)
        // userinfo trick: the real host is evil.com
        assertTrue(router.classify("https://jain.firstdj.com@evil.com/") is Route.External)
        // backslash tricks are rejected outright
        assertTrue(router.classify("https://evil.com\\@jain.firstdj.com/") is Route.Blocked)
    }

    // ------------------------------------------------------------------ WhatsApp
    @Test fun whatsappShareKeepsFullText() {
        val r = router.classify(
            "https://api.whatsapp.com/send?text=Hello%20World%21%20%F0%9F%99%8F%0ALine2%20%26%20more%3Dx"
        ) as Route.WhatsApp
        assertEquals("Hello World! 🙏\nLine2 & more=x", r.text)
        assertNull(r.phone)
    }

    @Test fun waMeVariants() {
        val share = router.classify("https://wa.me/?text=Join%20us") as Route.WhatsApp
        assertEquals("Join us", share.text)
        val chat = router.classify("https://wa.me/919876543210?text=Hi") as Route.WhatsApp
        assertEquals("919876543210", chat.phone)
        assertEquals("Hi", chat.text)
    }

    @Test fun whatsappSchemeAndWeb() {
        val a = router.classify("whatsapp://send?text=Hello%20there") as Route.WhatsApp
        assertEquals("Hello there", a.text)
        val b = router.classify("https://web.whatsapp.com/send?phone=911234567890&text=Yo") as Route.WhatsApp
        assertEquals("911234567890", b.phone)
        assertEquals("Yo", b.text)
    }

    @Test fun whatsappWebFallbackUrlEncodesText() {
        val route = Route.WhatsApp("whatsapp://send?text=x", "A & B?", null)
        assertEquals("https://api.whatsapp.com/send?text=A%20%26%20B%3F", LinkRouter.whatsAppWebUrl(route))
        val web = Route.WhatsApp("https://wa.me/?text=Hi", "Hi", null)
        assertEquals("https://wa.me/?text=Hi", LinkRouter.whatsAppWebUrl(web))
    }

    // ---------------------------------------------------------------------- Maps
    @Test fun googleMapsLinks() {
        val dir = router.classify(
            "https://www.google.com/maps/dir/?api=1&destination=12.9839,75.9892"
        ) as Route.Maps
        assertEquals("12.9839,75.9892", dir.destination)
        assertTrue(router.classify("https://maps.google.com/?q=Mumbai") is Route.Maps)
        assertTrue(router.classify("https://maps.app.goo.gl/AbCdEf") is Route.Maps)
        assertTrue(router.classify("https://goo.gl/maps/xyz") is Route.Maps)
        assertTrue(router.classify("https://www.google.co.in/maps/place/Jain+Temple") is Route.Maps)
        assertTrue(router.classify("geo:12.98,75.99?q=12.98,75.99") is Route.Maps)
        assertTrue(router.classify("google.navigation:q=12.98,75.99") is Route.Maps)
    }

    @Test fun nonMapsGoogleLinksAreExternal() {
        assertTrue(router.classify("https://www.google.com/search?q=maps") is Route.External)
        assertTrue(router.classify("https://www.google.com.evil.com/maps/x") is Route.External)
    }

    @Test fun coordinatesParsing() {
        assertEquals("12.9839" to "75.9892", Coordinates.parse("12.9839,75.9892"))
        assertEquals("-33.8" to "151.2", Coordinates.parse(" -33.8 , 151.2 "))
        assertNull(Coordinates.parse("95.0,10.0"))
        assertNull(Coordinates.parse("10.0,190.0"))
        assertNull(Coordinates.parse("Jain Temple, Mumbai"))
        assertNull(Coordinates.parse(null))
    }

    // -------------------------------------------------------------------- others
    @Test fun phoneMailSms() {
        assertTrue(router.classify("tel:+911234567890") is Route.Dial)
        assertTrue(router.classify("mailto:info@example.com?subject=Hi") is Route.Mail)
        assertTrue(router.classify("sms:+911234567890?body=Hi") is Route.Sms)
    }

    @Test fun otherWebsitesAreExternal() {
        assertTrue(router.classify("https://www.instagram.com/hdjd") is Route.External)
        assertTrue(router.classify("http://example.org/") is Route.External)
    }

    @Test fun dangerousSchemesAreBlocked() {
        assertTrue(router.classify("javascript:alert(1)") is Route.Blocked)
        assertTrue(router.classify("file:///data/data/com.firstdj.hdjd/") is Route.Blocked)
        assertTrue(router.classify("content://contacts/people") is Route.Blocked)
        assertTrue(router.classify("") is Route.Blocked)
        assertTrue(router.classify("   ") is Route.Blocked)
        assertTrue(router.classify("not a url") is Route.Blocked)
    }

    @Test fun intentSchemeIsRoutedForSanitising() {
        assertTrue(router.classify("intent://send?text=hi#Intent;scheme=whatsapp;end") is Route.IntentUri)
    }
}
