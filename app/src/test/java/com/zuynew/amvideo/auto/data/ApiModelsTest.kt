package com.zuynew.amvideo.auto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * api4.php is PHP, so the same flag can come back as a bool, a number or a
 * string. These cases pin down that all of them are read the same way.
 */
class ApiModelsTest {

    // The configuration the app actually ships, so the tests cannot drift from it.
    private val json = HttpAmVideoApi.defaultJson()

    @Test
    fun `a flag is read whether it arrives as bool, number or string`() {
        assertEquals(true, json.decodeFromString<OpenResponse>("""{"result":true}""").result)
        assertEquals(true, json.decodeFromString<OpenResponse>("""{"result":1}""").result)
        assertEquals(true, json.decodeFromString<OpenResponse>("""{"result":"1"}""").result)
        assertEquals(false, json.decodeFromString<OpenResponse>("""{"result":0}""").result)
        assertEquals(false, json.decodeFromString<OpenResponse>("""{"result":"0"}""").result)
        assertEquals(false, json.decodeFromString<OpenResponse>("""{"result":false}""").result)
        assertEquals(false, json.decodeFromString<OpenResponse>("""{"result":"false"}""").result)
    }

    @Test
    fun `an open with no result field has no verdict`() {
        assertNull(json.decodeFromString<OpenResponse>("""{"str":"открыто"}""").result)
    }

    @Test
    fun `the no-access answer parses as a refusal`() {
        val response = json.decodeFromString<PanelsResponse>(
            """{"str":"нет доступа!","result":false}""",
        )

        assertEquals(false, response.result)
        assertEquals("нет доступа!", response.str)
    }

    @Test
    fun `unknown fields on a panel row are ignored`() {
        val response = json.decodeFromString<PanelsResponse>(
            """{"result":1,"data":[{"id_shlag":"9","adress":"Двор","relay":"0","cam":false,"extra":"x"}]}""",
        )

        assertEquals(
            listOf(Door(id = "9", address = "Двор", relay = "0")),
            response.data.orEmpty().mapNotNull { it.toDoorOrNull() },
        )
    }

    /**
     * The full shape of a row as the live server sends it, including the fields
     * we do not read. Guessing this shape was the biggest assumption in the whole
     * feature. The identifiers, address and coordinates here are invented — only
     * the structure is real, which is all the parser cares about.
     */
    @Test
    fun `a real panel row maps to a door`() {
        val response = json.decodeFromString<PanelsResponse>(
            """
            {"result":true,"data":[{
              "sip":"10001","did":"79990000001","id_shlag":"1001",
              "adress":"1 шлагбаум (Примерная улица, 1к2)(10001)",
              "map_adress":null,
              "gps":"55.701234, 37.602345",
              "latitude":"55.701234","longitude":"37.602345",
              "description":"1 шлагбаум",
              "shlag_adress":"Примерная улица, 1к2",
              "cam":false,"induction_loop":false,"emergency_mode":false,
              "block":false,"pay_block":false,"manual_block":false,
              "channel":"","relay":"0"
            }]}
            """.trimIndent(),
        )

        val door = response.data.orEmpty().mapNotNull { it.toDoorOrNull() }.single()

        assertEquals("1001", door.id)
        // The clean street, not the noisy one with the SIP id on the end.
        assertEquals("Примерная улица, 1к2", door.address)
        assertEquals("0", door.relay)
        assertEquals(GeoPoint(55.701234, 37.602345), door.location)
    }

    /** map_adress comes back as an object on some rows and null on others. */
    @Test
    fun `a panel row whose map_adress is an object still parses`() {
        val response = json.decodeFromString<PanelsResponse>(
            """
            {"result":true,"data":[{
              "id_shlag":"1002","adress":"1 шлагбаум (Образцовая улица, 3/4)(10002)",
              "map_adress":{"house_number":"3/4","city":"Москва","country_code":"ru"},
              "gps":"55.712345, 37.612345",
              "latitude":"55.712345","longitude":"37.612345",
              "shlag_adress":"Образцовая улица, 3/4","relay":"0"
            }]}
            """.trimIndent(),
        )

        val door = response.data.orEmpty().mapNotNull { it.toDoorOrNull() }.single()

        assertEquals("Образцовая улица, 3/4", door.address)
        assertEquals(GeoPoint(55.712345, 37.612345), door.location)
    }

    @Test
    fun `the separate latitude and longitude win over the combined string`() {
        val response = json.decodeFromString<PanelsResponse>(
            """{"result":1,"data":[{"id_shlag":"9","relay":"1","gps":"0","latitude":"55.5","longitude":"37.5"}]}""",
        )

        assertEquals(
            GeoPoint(55.5, 37.5),
            response.data.orEmpty().mapNotNull { it.toDoorOrNull() }.single().location,
        )
    }

    @Test
    fun `a panel row's coordinates reach the door`() {
        val response = json.decodeFromString<PanelsResponse>(
            """{"result":1,"data":[{"id_shlag":"9","adress":"Двор","relay":"1","gps":"55.75393, 37.620795"}]}""",
        )

        val door = response.data.orEmpty().mapNotNull { it.toDoorOrNull() }.single()

        assertEquals(GeoPoint(55.75393, 37.620795), door.location)
    }

    @Test
    fun `a panel row without coordinates still yields a door`() {
        val response = json.decodeFromString<PanelsResponse>(
            """{"result":1,"data":[{"id_shlag":"9","adress":"Двор","relay":"1","gps":"0"}]}""",
        )

        assertNull(response.data.orEmpty().mapNotNull { it.toDoorOrNull() }.single().location)
    }

    @Test
    fun `a login answer reads change_device however it is spelled`() {
        assertEquals(
            true,
            json.decodeFromString<LoginResponse>("""{"result":0,"change_device":1}""").changeDevice,
        )
    }
}
