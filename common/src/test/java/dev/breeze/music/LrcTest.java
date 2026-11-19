package dev.breeze.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LrcTest {

    @Test
    void timedLinesInOrder() {
        List<Lrc.Line> l = Lrc.parse("[ar: Someone]\n[ti: Song]\n[00:12.50] First line\n[00:05.5]Zero\n[01:02.345] Later\n[00:20]No fraction\n");
        assertEquals(4, l.size());
        assertEquals(5_500, l.get(0).atMs);
        assertEquals("Zero", l.get(0).text);
        assertEquals(12_500, l.get(1).atMs);
        assertEquals("First line", l.get(1).text);
        assertEquals(20_000, l.get(2).atMs);
        assertEquals(62_345, l.get(3).atMs);
    }

    @Test
    void aRepeatedLineCarriesSeveralTimes() {
        List<Lrc.Line> l = Lrc.parse("[00:10.00][00:50.00] Chorus\n[00:30.00] Verse");
        assertEquals(3, l.size());
        assertEquals("Chorus", l.get(0).text);
        assertEquals("Verse", l.get(1).text);
        assertEquals("Chorus", l.get(2).text);
        assertEquals(50_000, l.get(2).atMs);
    }

    @Test
    void offsetAndWordTimes() {
        List<Lrc.Line> sooner = Lrc.parse("[offset:+500]\n[00:10.00] <00:10.00>Word <00:10.40>by <00:10.90>word");
        assertEquals(9_500, sooner.get(0).atMs);
        assertEquals("Word by word", sooner.get(0).text);
        assertEquals(10_250, Lrc.parse("[offset: -250]\n[00:10.00] x").get(0).atMs);
        assertEquals(0, Lrc.parse("[offset:9000]\n[00:01.00] x").get(0).atMs, "never before the start");
    }

    @Test
    void theLineAtAPosition() {
        List<Lrc.Line> l = Lrc.parse("[00:10.00] a\n[00:20.00] b\n[00:30.00] c");
        assertEquals(-1, Lrc.indexAt(l, 0));
        assertEquals(-1, Lrc.indexAt(l, 9_999));
        assertEquals(0, Lrc.indexAt(l, 10_000));
        assertEquals(1, Lrc.indexAt(l, 29_999));
        assertEquals(2, Lrc.indexAt(l, 600_000));
        assertEquals(-1, Lrc.indexAt(List.of(), 5));
    }

    @Test
    void textFromTheInternetIsMadeSafe() {
        List<Lrc.Line> l = Lrc.parse("[00:01.00] \u00a7kHidden\u00a7r  and\tspaced\u0007 out");
        assertEquals("Hidden and spaced out", l.get(0).text);
        String longLine = "[00:02.00] " + "x".repeat(1_000);
        assertEquals(Lrc.MAX_CHARS, Lrc.parse(longLine).get(0).text.length());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < Lrc.MAX_LINES + 50; i++) many.append("[00:01.00] line\n");
        assertEquals(Lrc.MAX_LINES, Lrc.parse(many.toString()).size());
        assertTrue(Lrc.parse(null).isEmpty());
        assertTrue(Lrc.parse("no times here\njust words").isEmpty());
    }

    @Test
    void anEmptyTimedLineIsAGapInTheSinging() {
        List<Lrc.Line> l = Lrc.parse("[00:10.00] words\n[00:15.00]\n[00:40.00] more");
        assertEquals("", l.get(1).text);
        assertEquals(1, Lrc.indexAt(l, 20_000));
    }

    @Test
    void plainLyrics() {
        assertEquals(List.of("one", "", "two ❤"), Lrc.plain("one\r\n\r\n  two ❤ \n\n"));
        assertTrue(Lrc.plain(null).isEmpty());
    }

    @Test
    void wrapping() {
        java.util.function.ToIntFunction<String> chars = String::length;
        assertEquals(List.of("the quick", "brown fox"), Lrc.wrap("the quick brown fox", 10, 3, chars));
        assertEquals(List.of("the quick"), Lrc.wrap("the quick brown fox", 10, 1, chars));
        assertEquals(List.of("abcde", "ok"), Lrc.wrap("abcdefghij ok", 5, 3, chars));
        assertEquals(List.of(), Lrc.wrap("", 10, 3, chars));
    }
}
