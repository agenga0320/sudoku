package com.example.sudoku

/** State of one game in progress. Free of Android dependencies so it can be unit tested. */
class Game(val difficulty: Difficulty, val solution: IntArray, val givens: IntArray) {
    val values: IntArray = givens.copyOf()

    /** Pencil marks per cell, as a bitmask with bit d set for digit d. */
    val notes = IntArray(Sudoku.CELLS)
    var elapsedSeconds = 0

    private val undoStack = ArrayDeque<IntArray>()

    val isSolved: Boolean get() = values.contentEquals(solution)
    val isFull: Boolean get() = values.none { it == 0 }
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun isGiven(i: Int) = givens[i] != 0
    fun countOf(digit: Int) = values.count { it == digit }

    /** Puts [digit] in cell [i]; entering the digit already there removes it. */
    fun enter(i: Int, digit: Int): Boolean {
        if (isSolved || isGiven(i)) return false
        snapshot()
        if (values[i] == digit) {
            values[i] = 0
            return true
        }
        // Wrong digits are treated exactly like right ones, so nothing gives them away.
        values[i] = digit
        notes[i] = 0
        val bit = 1 shl digit
        for (peer in Sudoku.peers[i]) notes[peer] = notes[peer] and bit.inv()
        return true
    }

    /**
     * Adds or removes the note [digit] in cell [i]. A note is not added when the digit is
     * already placed in the cell's row, column or box, since that is almost always a slip.
     */
    fun toggleNote(i: Int, digit: Int): Boolean {
        if (isSolved || isGiven(i) || values[i] != 0) return false
        val bit = 1 shl digit
        val adding = notes[i] and bit == 0
        if (adding && Sudoku.peers[i].any { values[it] == digit }) return false
        snapshot()
        notes[i] = notes[i] xor bit
        return true
    }

    fun erase(i: Int): Boolean {
        if (isSolved || isGiven(i) || (values[i] == 0 && notes[i] == 0)) return false
        snapshot()
        values[i] = 0
        notes[i] = 0
        return true
    }

    fun undo(): Boolean {
        val state = undoStack.removeLastOrNull() ?: return false
        state.copyInto(values, 0, 0, Sudoku.CELLS)
        state.copyInto(notes, 0, Sudoku.CELLS, 2 * Sudoku.CELLS)
        return true
    }

    private fun snapshot() {
        undoStack.addLast(values + notes)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun encode(): String = listOf(
        difficulty.name,
        elapsedSeconds,
        solution.joinToString(""),
        givens.joinToString(""),
        values.joinToString(""),
        notes.joinToString(","),
    ).joinToString(";")

    companion object {
        private const val MAX_UNDO = 200

        /** Restores a game saved with [encode]; returns null if the data is unusable. */
        fun decode(data: String): Game? = runCatching {
            // Saves from versions up to 1.1.0 carry a mistake count as the second field.
            val parts = data.split(';').let { if (it.size == 7) it.filterIndexed { i, _ -> i != 1 } else it }
            require(parts.size == 6)
            val game = Game(Difficulty.valueOf(parts[0]), digits(parts[2]), digits(parts[3]))
            game.elapsedSeconds = parts[1].toInt()
            digits(parts[4]).copyInto(game.values)
            val notes = parts[5].split(',').map { it.toInt() }
            require(notes.size == Sudoku.CELLS)
            notes.toIntArray().copyInto(game.notes)
            game
        }.getOrNull()

        private fun digits(text: String): IntArray {
            require(text.length == Sudoku.CELLS && text.all { it in '0'..'9' })
            return IntArray(Sudoku.CELLS) { text[it] - '0' }
        }
    }
}
