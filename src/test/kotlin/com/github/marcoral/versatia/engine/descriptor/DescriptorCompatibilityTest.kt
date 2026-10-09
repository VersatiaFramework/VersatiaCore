package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DescriptorCompatibilityTest {
    private val supported = DescriptorVersion(2, 3)

    private fun check(version: String) = DescriptorCompatibility.check("Alpha", DescriptorVersion.parse(version), supported)

    @Test
    fun `the supported version passes`() = check("2.3")

    @Test
    fun `an older version asks to rebuild the plugin`() {
        val error = assertFailsWith<EnhancementException> { check("2.1") }
        assertContains(error.message!!, "Alpha was built for descriptor format 2.1, but this VersatiaCore supports 2.3")
        assertContains(error.message!!, "Rebuild the plugin")
    }

    @Test
    fun `a newer version asks to update VersatiaCore`() {
        assertContains(assertFailsWith<EnhancementException> { check("3.0") }.message!!, "Update VersatiaCore")
        assertContains(assertFailsWith<EnhancementException> { check("2.4") }.message!!, "Update VersatiaCore")
    }

    @Test
    fun `versions order and print naturally`() {
        assertEquals("1.2", DescriptorVersion.parse(" 1.2 ").toString())
        assert(DescriptorVersion(1, 10) > DescriptorVersion(1, 9))
        assertFailsWith<IllegalArgumentException> { DescriptorVersion.parse("1.2.3") }
        assertFailsWith<IllegalArgumentException> { DescriptorVersion.parse("1.x") }
    }
}
