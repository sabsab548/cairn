package org.cairn.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectIdTest {

    @Test
    fun none_round_trips() {
        assertEquals(ObjectId.NONE, ObjectId.fromBytes(ObjectId.NONE.toBytes()))
    }

    @Test
    fun author_zero_is_reserved_for_none() {
        // A real object can never use author 0, so 0x00000000 stays unambiguous.
        assertFailsWith<IllegalArgumentException> {
            ObjectId.fromBytes(byteArrayOf(0x02, 0x00, 0x00, 0x05)) // Pin, author 0, counter 5
        }
    }

    @Test
    fun other_authors_round_trip() {
        val id = ObjectId(ObjectType.Pin, authorId = 7, counter = 42)
        assertEquals(id, ObjectId.fromBytes(id.toBytes()))
    }
}
