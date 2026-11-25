package com.example.sudoku

import kotlin.random.Random

enum class Difficulty(val clues: Int) {
    EASY(40),
    MEDIUM(32),
    HARD(26),

    /** A puzzle imported by the user rather than generated. */
    CUSTOM(0),

    /** One of the well-known hard puzzles in [Classics]. */
    CLASSIC(0);

    companion object {
        val generated = listOf(EASY, MEDIUM, HARD)
    }
}

/** A well-known hard puzzle. Progress is saved under [id], so an id must never change. */
class Classic(val id: String, val name: String, val givens: String)

/**
 * Famous hard puzzles the player can pick by name. The first four grids were checked against
 * the pages cited; the last two come without a readable source and rest on the unit test
 * that every grid here has exactly one solution.
 */
object Classics {
    val all = listOf(
        // sudokuwiki.org/Arto_Inkala_Sudoku
        Classic(
            "inkala2012", "Arto Inkala 2012（號稱世界最難）",
            "800000000003600000070090200050007000000045700000100030001000068008500010090000400",
        ),
        // sudokuwiki.org/Escargot
        Classic(
            "escargot", "AI Escargot（Arto Inkala，2006）",
            "100007090030020008009600500005300900010080002600004000300000010040000007007000300",
        ),
        // sci.math thread "hard sudoku, 'Platinum Blonde'", 2013
        Classic(
            "platinum", "Platinum Blonde",
            "000000012000000003002300400001800005060070800000009000008500000900040500470006000",
        ),
        // stolaf.edu/people/hansonr/sudoku/easter_monster.htm
        Classic(
            "easter", "Easter Monster",
            "100000089000009002000000450007600000030040000900002005004070000500008010060300000",
        ),
        Classic(
            "nugget", "Golden Nugget",
            "000000039000001005003050800008090006070002000100400000009080050020000600400700000",
        ),
        Classic(
            "bruteforce", "暴力破解剋星（17 個提示）",
            "000000000000003085001020000000507000004000100090000000500000073002010000000040009",
        ),
    )
}

/** Grids are 81 ints in row-major order; 0 means empty. */
class Puzzle(val solution: IntArray, val givens: IntArray)

object Sudoku {
    const val CELLS = 81
    private const val ALL_DIGITS = 0x3FE // bits 1..9

    fun boxOf(i: Int) = (i / 27) * 3 + (i % 9) / 3

    /** Cells sharing a row, column or box with each cell (excluding itself). */
    val peers: Array<IntArray> = Array(CELLS) { i ->
        (0 until CELLS).filter { j ->
            j != i && (j / 9 == i / 9 || j % 9 == i % 9 || boxOf(j) == boxOf(i))
        }.toIntArray()
    }

    /** Generates a puzzle with exactly one solution. */
    fun generate(difficulty: Difficulty, random: Random = Random.Default): Puzzle {
        val solution = IntArray(CELLS)
        Search(solution, random).run(1)

        // Dig cells out in random order, keeping only removals that leave the solution unique.
        val givens = solution.copyOf()
        var clues = CELLS
        for (i in (0 until CELLS).shuffled(random)) {
            if (clues <= difficulty.clues) break
            val digit = givens[i]
            givens[i] = 0
            if (countSolutions(givens, 2) == 1) clues-- else givens[i] = digit
        }
        return Puzzle(solution, givens)
    }

    /**
     * Counts solutions of [grid], stopping once [limit] have been found.
     * The givens must not conflict with each other (see [hasConflict]).
     */
    fun countSolutions(grid: IntArray, limit: Int = 2): Int = Search(grid.copyOf(), null).run(limit)

    /**
     * Reads a puzzle typed as 9 rows of 9 digits with 0 for empty cells.
     * Anything that is not a digit is ignored; returns null unless exactly 81 digits remain.
     */
    fun parse(text: String): IntArray? {
        val digits = text.filter { it in '0'..'9' }
        if (digits.length != CELLS) return null
        return IntArray(CELLS) { digits[it] - '0' }
    }

    /** Every run of 9 consecutive lines of exactly 9 digits in [text] (such as an AI's reply), in order. */
    fun grids(text: String): List<String> {
        val found = ArrayList<String>()
        val run = ArrayList<String>()
        for (line in text.lines().map { it.trim() } + "") {
            if (line.length == 9 && line.all { it in '0'..'9' }) {
                run += line
                continue
            }
            if (run.size >= 9) found += run.takeLast(9).joinToString("\n")
            run.clear()
        }
        return found
    }

    /** True if the same digit appears twice in a row, column or box. */
    fun hasConflict(grid: IntArray): Boolean =
        (0 until CELLS).any { i -> grid[i] != 0 && peers[i].any { grid[it] == grid[i] } }

    /** Returns the solution of [grid], or null if it has none (conflicting givens included). */
    fun solve(grid: IntArray): IntArray? {
        if (hasConflict(grid)) return null
        val solution = grid.copyOf()
        return if (Search(solution, null).run(1) == 1) solution else null
    }

    /**
     * Backtracking search that always branches on the cell with the fewest candidates.
     * Fills [grid] in place; when the limit is reached the grid holds the last solution found.
     */
    private class Search(private val grid: IntArray, private val random: Random?) {
        private val rows = IntArray(9)
        private val cols = IntArray(9)
        private val boxes = IntArray(9)
        private var found = 0
        private var limit = 0

        init {
            for (i in grid.indices) if (grid[i] != 0) toggle(i, grid[i])
        }

        private fun toggle(i: Int, digit: Int) {
            val bit = 1 shl digit
            rows[i / 9] = rows[i / 9] xor bit
            cols[i % 9] = cols[i % 9] xor bit
            boxes[boxOf(i)] = boxes[boxOf(i)] xor bit
        }

        fun run(limit: Int): Int {
            this.limit = limit
            found = 0
            step()
            return found
        }

        private fun step(): Boolean {
            var best = -1
            var bestMask = 0
            var bestCount = 10
            for (i in 0 until CELLS) {
                if (grid[i] != 0) continue
                val mask = ALL_DIGITS and (rows[i / 9] or cols[i % 9] or boxes[boxOf(i)]).inv()
                val count = Integer.bitCount(mask)
                if (count == 0) return false
                if (count < bestCount) {
                    best = i
                    bestMask = mask
                    bestCount = count
                    if (count == 1) break
                }
            }
            if (best < 0) return ++found >= limit

            val digits = (1..9).filter { bestMask and (1 shl it) != 0 }
            for (digit in if (random != null) digits.shuffled(random) else digits) {
                grid[best] = digit
                toggle(best, digit)
                if (step()) return true
                toggle(best, digit)
                grid[best] = 0
            }
            return false
        }
    }
}
