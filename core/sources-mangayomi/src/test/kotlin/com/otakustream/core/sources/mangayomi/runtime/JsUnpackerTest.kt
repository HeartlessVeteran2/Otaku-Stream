package com.otakustream.core.sources.mangayomi.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class JsUnpackerTest {

    @Test
    fun `unpacks a base-2 packed payload`() {
        val packed = "eval(function(p,a,c,k,e,d){}('0 1',2,2,'hello|world'.split('|'),0,{}))"
        assertEquals("hello world", JsUnpacker.unpack(packed))
    }

    @Test
    fun `returns input unchanged when not packed`() {
        val plain = "var a = 1; console.log(a);"
        assertEquals(plain, JsUnpacker.unpack(plain))
    }

    @Test
    fun `keeps original token when dictionary slot is empty`() {
        // Word list has an empty slot at index 1, so token "1" falls back to itself.
        val packed = "eval(function(p,a,c,k,e,d){}('0 1',2,2,'kept|'.split('|'),0,{}))"
        assertEquals("kept 1", JsUnpacker.unpack(packed))
    }
}
