package com.github.marcoral.versatia.messages

import kotlin.test.Test
import kotlin.test.assertEquals

class MissingMessageExceptionTest {
    @Test
    fun `describes what is available`() {
        assertEquals(
            "Plugin Shop has no message \"shop.byu\"; the messages under \"shop\" are: shop.bought, shop.closed",
            MissingMessageException("Shop", "shop.byu", setOf("shop.bought", "shop.closed", "admin.kick")).message,
        )
        assertEquals(
            "Plugin Shop has no message \"bank.open\"; it has 3 messages, none starting with \"bank.\"",
            MissingMessageException("Shop", "bank.open", setOf("shop.bought", "shop.closed", "admin.kick")).message,
        )
        assertEquals(
            "Plugin Shop has no message \"x\"; it has no messages at all; is there a messages/ directory in its data folder?",
            MissingMessageException("Shop", "x", emptySet()).message,
        )
    }
}
