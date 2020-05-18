import kotlinx.cinterop.*
import kotlin.test.*
import objcTests.*

private class NSObjectSubClass : NSObject() {
    val x = 111
}

@Test
fun testStructWithNSObject() {
    memScoped {
        val struct = alloc<CStructWithNSObjects>()

        struct.nsString = "hello"
        assertEquals("hello", struct.nsString)
        struct.nsString = null
        assertEquals(null, struct.nsString)

        struct.`object` = NSObjectSubClass()
        assertEquals(111, (struct.`object` as NSObjectSubClass).x)
        struct.`object` = null
        assertEquals(null, struct.`object`)

        struct.block = { 3 }
        assertEquals(3, struct.block!!())
        struct.block = null
        assertEquals(null, struct.block)

        struct.array = null
        assertEquals(null, struct.array)
        struct.array = listOf(1, 2, 3)
        assertEquals(listOf(1, 2, 3), struct.array)

        struct.set = null
        assertEquals(null, struct.set)
        struct.set = setOf("hello", "world")
        assertEquals(setOf("hello", "world"), struct.set)

        struct.dictionary = null
        assertEquals(null, struct.dictionary)
        struct.dictionary = mapOf("k1" to "v1", "k2" to "v2")
        assertEquals(mapOf("k1" to "v1", "k2" to "v2"), struct.dictionary)

        struct.mutableArray = null
        assertEquals(null, struct.mutableArray)
        struct.mutableArray = mutableListOf(1, 2)
        struct.mutableArray!! += 3
        assertEquals(mutableListOf(1, 2, 3), struct.mutableArray)

        // Check that subtyping via Nothing-returning functions does not break compiler.
        assertFailsWith<NotImplementedError> {
            struct.nsString = TODO()
            struct.`object` = TODO()
            struct.block = TODO()
            struct.array = TODO()
            struct.set = TODO()
            struct.dictionary = TODO()
            struct.mutableArray = TODO()
        }
    }
}