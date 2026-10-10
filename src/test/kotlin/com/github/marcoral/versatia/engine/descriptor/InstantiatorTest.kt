package com.github.marcoral.versatia.engine.descriptor

import kotlin.test.Test
import kotlin.test.assertSame

private object Hidden

class InstantiatorTest {
    private val loader = InstantiatorTest::class.java.classLoader

    @Test
    fun `reads the instance of a private object`() =
        assertSame(Hidden, Instantiator.ObjectInstance(Hidden::class.java.name).create(loader, emptyList()))

    @Test
    fun `names the object directly when it is reachable`() =
        assertSame(Hidden, Instantiator.Direct { Hidden }.create(loader, emptyList()))
}
