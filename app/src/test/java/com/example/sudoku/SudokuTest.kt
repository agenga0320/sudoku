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
        assertTrue(game.isFull)
        assertFalse(game.enter(0, 1))
    }

    @Test
    fun fullBoardWithWrongDigitIsNotSolved() {
        val puzzle = Sudoku.generate(Difficulty.EASY, Random(1))
        val game = Game(Difficulty.EASY, puzzle.solution, puzzle.givens)
        val first = puzzle.givens.indexOf(0)
        for (i in 0 until Sudoku.CELLS) {
            if (game.isGiven(i)) continue
            assertFalse(game.isFull)
            game.enter(i, if (i == first) puzzle.solution[i] % 9 + 1 else puzzle.solution[i])
        }
        assertTrue(game.isFull)
        assertFalse(game.isSolved)
    }

    @Test
    fun wrongDigitsNotesAndUndo() {
        val puzzle = Sudoku.generate(Difficulty.MEDIUM, Random(2))
        val withGivens = Game(Difficulty.MEDIUM, puzzle.solution, puzzle.givens)
        val given = puzzle.givens.indexOfFirst { it != 0 }
        assertFalse(withGivens.enter(given, puzzle.givens[given] % 9 + 1))
        assertFalse(withGivens.toggleNote(given, 1))
        assertFalse(withGivens.canUndo)

        // An empty board, so that no given gets in the way of the notes below.
        val game = Game(Difficulty.CUSTOM, puzzle.solution, IntArray(Sudoku.CELLS))
        val cell = 0
        val peer = 1
        val right = puzzle.solution[cell]
        val wrong = right % 9 + 1

        // Placing a digit clears it from the notes of its peers, whether it is right or wrong.
        assertTrue(game.toggleNote(peer, right))
        assertTrue(game.toggleNote(peer, wrong))
        assertTrue(game.enter(cell, wrong))
        assertEquals(wrong, game.values[cell])
        assertEquals(1 shl right, game.notes[peer])
        assertTrue(game.undo())
        assertEquals(0, game.values[cell])
        assertEquals((1 shl wrong) or (1 shl right), game.notes[peer])

        assertTrue(game.enter(cell, right))
        assertEquals(1 shl wrong, game.notes[peer])
        assertTrue(game.undo())

        assertTrue(game.erase(peer))
        assertEquals(0, game.notes[peer])
        assertFalse(game.erase(peer))
    }

    @Test
    fun notesConflictingWithPlacedDigitsAreNotAdded() {
        val puzzle = Sudoku.generate(Difficulty.EASY, Random(5))
        val game = Game(Difficulty.CUSTOM, puzzle.solution, IntArray(Sudoku.CELLS))
        assertTrue(game.enter(0, 5))

        assertFalse(game.toggleNote(8, 5))  // same row
        assertFalse(game.toggleNote(72, 5)) // same column
        assertFalse(game.toggleNote(20, 5)) // same box
        assertEquals(0, game.notes[8] or game.notes[72] or game.notes[20])
        assertTrue(game.undo())             // the rejected notes left nothing to undo but the 5
        assertFalse(game.canUndo)
        assertTrue(game.enter(0, 5))

        assertTrue(game.toggleNote(40, 5))  // not a peer
        assertTrue(game.toggleNote(8, 6))   // a different digit
        assertTrue(game.toggleNote(8, 6))   // and it can be removed again
        assertEquals(0, game.notes[8])

        // Givens block notes in the same way.
        val given = puzzle.solution.copyOf().also { for (i in 1 until Sudoku.CELLS) it[i] = 0 }
        val withGiven = Game(Difficulty.CUSTOM, puzzle.solution, given)
        assertFalse(withGiven.toggleNote(1, given[0]))
        assertTrue(withGiven.toggleNote(1, given[0] % 9 + 1))
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
        assertEquals(123, copy.elapsedSeconds)
        assertArrayEquals(game.solution, copy.solution)
        assertArrayEquals(game.givens, copy.givens)
        assertArrayEquals(game.values, copy.values)
        assertArrayEquals(game.notes, copy.notes)

        // Saves written before the mistake counter was removed still load.
        val legacy = game.encode().replaceFirst(";", ";5;")
        val fromLegacy = Game.decode(legacy)!!
        assertEquals(123, fromLegacy.elapsedSeconds)
        assertArrayEquals(game.values, fromLegacy.values)
        assertArrayEquals(game.notes, fromLegacy.notes)

        assertNull(Game.decode("garbage"))
        assertNull(Game.decode(""))
    }

    @Test
    fun updateVersionsCompareAsNumbers() {
        assertTrue(Update.isNewer("1.10.0", "1.9.3"))
        assertTrue(Update.isNewer("1.4.1", "1.4"))
        assertFalse(Update.isNewer("1.4.0", "1.4.0"))
        assertFalse(Update.isNewer("1.3.9", "1.4.0"))
    }

    @Test
    fun onlyThisProjectsReleaseApkIsTrusted() {
        val base = "https://github.com/agenga0320/sudoku/releases/download"
        assertTrue(Update.isTrusted("1.4.1", "$base/v1.4.1/Sudoku-v1.4.1-release.apk"))
        assertFalse(Update.isTrusted("1.4.1", "$base/v1.4.1/Sudoku-v1.4.1-release.apk?x=1"))
        assertFalse(Update.isTrusted("1.4.1", "$base/v1.4.0/Sudoku-v1.4.0-release.apk"))
        assertFalse(Update.isTrusted("1.4.1", "https://example.com/v1.4.1/Sudoku-v1.4.1-release.apk"))
        assertFalse(Update.isTrusted("1.4", "$base/v1.4/Sudoku-v1.4-release.apk"))
    }

    @Test
    fun redoRestoresUndoneMovesUntilANewMove() {
        val puzzle = Sudoku.generate(Difficulty.EASY, Random(3))
        val game = Game(Difficulty.EASY, puzzle.solution, puzzle.givens)
        val (a, b) = (0 until Sudoku.CELLS).filter { puzzle.givens[it] == 0 }
        assertFalse(game.redo())

        game.enter(a, puzzle.solution[a])
        game.enter(b, puzzle.solution[b])
        assertTrue(game.undo())
        assertTrue(game.undo())
        assertEquals(0, game.values[a])

        assertTrue(game.redo())
        assertEquals(puzzle.solution[a], game.values[a])
        assertEquals(0, game.values[b])
        assertTrue(game.canRedo)

        // Undoing a redo and redoing it again both work.
        assertTrue(game.undo())
        assertTrue(game.redo())

        // A new move discards what was left to redo.
        game.toggleNote(b, puzzle.solution[b])
        assertFalse(game.canRedo)
        assertFalse(game.redo())
    }
}
