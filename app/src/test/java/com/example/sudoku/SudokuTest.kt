package com.example.sudoku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SudokuTest {

    private fun assertValidSolution(grid: IntArray) {
        for (unit in 0 until 9) {
            val row = (0 until 9).map { grid[unit * 9 + it] }.toSet()
            val col = (0 until 9).map { grid[it * 9 + unit] }.toSet()
            val box = (0 until Sudoku.CELLS).filter { Sudoku.boxOf(it) == unit }.map { grid[it] }.toSet()
            assertEquals((1..9).toSet(), row)
            assertEquals((1..9).toSet(), col)
            assertEquals((1..9).toSet(), box)
        }
    }

    @Test
    fun generatedPuzzlesAreValidAndUnique() {
        for (difficulty in Difficulty.generated) {
            repeat(10) { seed ->
                val puzzle = Sudoku.generate(difficulty, Random(seed))
                assertValidSolution(puzzle.solution)
                for (i in 0 until Sudoku.CELLS) {
                    assertTrue(puzzle.givens[i] == 0 || puzzle.givens[i] == puzzle.solution[i])
                }
                assertEquals(1, Sudoku.countSolutions(puzzle.givens))
                val clues = puzzle.givens.count { it != 0 }
                assertTrue("$difficulty has $clues clues", clues >= difficulty.clues && clues <= difficulty.clues + 6)
            }
        }
    }

    @Test
    fun countSolutionsDetectsAmbiguousAndImpossibleGrids() {
        assertEquals(2, Sudoku.countSolutions(IntArray(Sudoku.CELLS)))
        val deadEnd = IntArray(Sudoku.CELLS).also {
            for (d in 1..8) it[d] = d // row 0 needs a 9 in cell 0
            it[9 * 4] = 9             // but column 0 already has one
        }
        assertFalse(Sudoku.hasConflict(deadEnd))
        assertEquals(0, Sudoku.countSolutions(deadEnd))
        assertNull(Sudoku.solve(deadEnd))
    }

    @Test
    fun parseAcceptsNineRowsOfNineDigits() {
        val text = """
            530070000
            600195000
            098000060
            800060003
            400803001
            700020006
            060000280
            000419005
            000080079
        """.trimIndent()
        val grid = Sudoku.parse(text)
        assertNotNull(grid)
        assertEquals(5, grid!![0])
        assertEquals(9, grid[80])
        assertEquals(30, grid.count { it != 0 })
        assertFalse(Sudoku.hasConflict(grid))
        assertEquals(1, Sudoku.countSolutions(grid))
        val solution = Sudoku.solve(grid)!!
        assertValidSolution(solution)
        assertEquals("534678912", solution.take(9).joinToString(""))

        // Separators and code fences around the digits are tolerated.
        assertArrayEquals(grid, Sudoku.parse("```\n" + text.replace("", " ") + "\n```"))
        assertArrayEquals(grid, Sudoku.parse(text.replace("\n", "\r\n")))

        assertNull(Sudoku.parse(text.dropLast(1)))
        assertNull(Sudoku.parse(text + "1"))
        assertNull(Sudoku.parse(""))
    }

    @Test
    fun conflictingGivensAreRejected() {
        val row = IntArray(Sudoku.CELLS).also { it[0] = 5; it[8] = 5 }
        val col = IntArray(Sudoku.CELLS).also { it[0] = 5; it[72] = 5 }
        val box = IntArray(Sudoku.CELLS).also { it[0] = 5; it[20] = 5 }
        for (grid in listOf(row, col, box)) {
            assertTrue(Sudoku.hasConflict(grid))
            assertNull(Sudoku.solve(grid))
        }
        assertFalse(Sudoku.hasConflict(IntArray(Sudoku.CELLS)))
    }

    @Test
    fun playingTheSolutionSolvesTheGame() {
        val puzzle = Sudoku.generate(Difficulty.EASY, Random(1))
        val game = Game(Difficulty.EASY, puzzle.solution, puzzle.givens)
        for (i in 0 until Sudoku.CELLS) {
            if (!game.isGiven(i)) assertTrue(game.enter(i, puzzle.solution[i]))
        }
        assertTrue(game.isSolved)
        assertEquals(0, game.mistakes)
        assertFalse(game.enter(0, 1))
    }

    @Test
    fun mistakesNotesAndUndo() {
        val puzzle = Sudoku.generate(Difficulty.MEDIUM, Random(2))
        val game = Game(Difficulty.MEDIUM, puzzle.solution, puzzle.givens)
        val cell = puzzle.givens.indexOf(0)
        val given = puzzle.givens.indexOfFirst { it != 0 }
        val right = puzzle.solution[cell]
        val wrong = right % 9 + 1

        assertFalse(game.enter(given, wrong))
        assertFalse(game.canUndo)

        assertTrue(game.enter(cell, wrong))
        assertTrue(game.isWrong(cell))
        assertEquals(1, game.mistakes)
        assertTrue(game.undo())
        assertEquals(0, game.values[cell])

        // A correct digit clears that digit from the notes of its peers.
        val peer = Sudoku.peers[cell].first { !game.isGiven(it) }
        assertTrue(game.toggleNote(peer, right))
        assertTrue(game.toggleNote(peer, wrong))
        assertTrue(game.enter(cell, right))
        assertEquals(1 shl wrong, game.notes[peer])
        assertTrue(game.undo())
        assertEquals((1 shl wrong) or (1 shl right), game.notes[peer])

        assertTrue(game.erase(peer))
        assertEquals(0, game.notes[peer])
        assertFalse(game.erase(peer))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val puzzle = Sudoku.generate(Difficulty.HARD, Random(4))
        val game = Game(Difficulty.HARD, puzzle.solution, puzzle.givens)
        val cell = puzzle.givens.indexOf(0)
        game.toggleNote(cell, 3)
        game.enter(puzzle.givens.lastIndexOf(0), 7)
        game.elapsedSeconds = 123

        val copy = Game.decode(game.encode())!!
        assertEquals(game.difficulty, copy.difficulty)
        assertEquals(game.mistakes, copy.mistakes)
        assertEquals(123, copy.elapsedSeconds)
        assertArrayEquals(game.solution, copy.solution)
        assertArrayEquals(game.givens, copy.givens)
        assertArrayEquals(game.values, copy.values)
        assertArrayEquals(game.notes, copy.notes)

        assertNull(Game.decode("garbage"))
        assertNull(Game.decode(""))
    }
}
