package com.example.sudoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoachTest {

    private val game = Sudoku.generate(Difficulty.EASY, Random(1)).let { Game(Difficulty.EASY, it.solution, it.givens) }
    private val empty = game.values.indexOfFirst { it == 0 }

    private fun reply(cell: Int, digit: Int) =
        "[1] 看第一橫排。\n[2] 想想哪個數字只剩一個位置。\n[3] 先看……\n[CHECK] r${cell / 9 + 1}c${cell % 9 + 1}=$digit"

    @Test
    fun parsesAllSections() {
        val hint = Coach.parse(reply(empty, game.solution[empty]))
        assertNotNull(hint)
        assertEquals(listOf("看第一橫排。", "想想哪個數字只剩一個位置。", "先看……"), hint!!.levels)
        assertEquals(empty, hint.cell)
        assertEquals(game.solution[empty], hint.digit)
    }

    @Test
    fun rejectsIncompleteReplies() {
        assertNull(Coach.parse("[1] 看第一橫排。\n[2] 想想看。\n[CHECK] r1c1=5"))
        assertNull(Coach.parse("[1] a\n[2] b\n[3] c"))
        assertNull(Coach.parse("[1] a\n[2] b\n[3] c\n[CHECK] 不知道"))
    }

    @Test
    fun soundOnlyWhenDigitMatchesSolution() {
        val right = game.solution[empty]
        assertTrue(Coach.isSound(Coach.parse(reply(empty, right))!!, game))
        assertFalse(Coach.isSound(Coach.parse(reply(empty, right % 9 + 1))!!, game))
        val given = game.values.indexOfFirst { it != 0 }
        assertFalse(Coach.isSound(Coach.parse(reply(given, game.solution[given]))!!, game))
    }

    @Test
    fun playersNotesAreDescribedOnlyWhenThereAreSome() {
        val g = Game(Difficulty.EASY, game.solution, game.givens)
        assertFalse(Coach.describe(g, -1).contains("筆記"))
        val digit = Coach.candidates(g.values, empty).first()
        assertTrue(g.toggleNote(empty, digit))
        val name = "r${empty / 9 + 1}c${empty % 9 + 1}"
        assertTrue(Coach.describe(g, -1).substringAfter("筆記").contains("$name{$digit}"))
    }

    @Test
    fun chatLinkCarriesTheQuestionOnlyWhereTheSiteTakesIt() {
        val question = Coach.chatPrompt(game, empty)
        assertTrue(question.contains("r1: "))
        val link = ChatSite.CHATGPT.link("第 1 橫排 a b")
        assertTrue(link.startsWith("https://chatgpt.com/?q=%E7%AC%AC%201%20"))
        assertFalse(link.contains("+") || link.contains(" "))
        assertEquals("https://gemini.google.com/app", ChatSite.GEMINI.link(question))
    }

    @Test
    fun candidatesExcludePeersAndIncludeSolution() {
        val candidates = Coach.candidates(game.values, empty)
        assertTrue(game.solution[empty] in candidates)
        assertTrue(candidates.none { d -> Sudoku.peers[empty].any { game.values[it] == d } })
        // The board is nine lines, and the empty cell shows its candidates in braces.
        val described = Coach.describe(game, -1)
        assertEquals(9, described.lines().count { it.matches(Regex("""r[1-9]: .+""")) })
        assertTrue(described.contains("{${candidates.joinToString("")}}"))
    }
}
