package com.example.sudoku

/** State of one game in progress. Free of Android dependencies so it can be unit tested. */
class Game(val difficulty: Difficulty, val solution: IntArray, val givens: IntArray) {
    val values: IntArray = givens.copyOf()

    /** Pencil marks per cell, as a bitmask with bit d set for digit d. */
    val notes = IntArray(Sudoku.CELLS)
    var mistakes = 0
    var elapsedSeconds = 0

    private val undoStack = ArrayDeque<IntArray>()

    val isSolved: Boolean get() = values.contentEquals(solution)
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun isGiven(i: Int) = givens[i] != 0
    fun isWrong(i: Int) = values[i] != 0 && values[i] != solution[i]
    fun countOf(digit: Int) = values.count { it == digit }

    /** Puts [digit] in cell [i]; entering the digit already there removes it. */
    fun enter(i: Int, digit: Int): Boolean {
        if (isSolved || isGiven(i)) return false
        snapshot()
        if (values[i] == digit) {
            values[i] = 0
            return true
        }
        place(i, digit)
        if (digit != solution[i]) mistakes++
        return true
    }

    fun toggleNote(i: Int, digit: Int): Boolean {
        if (isSolved || isGiven(i) || values[i] != 0) return false
        snapshot()
        notes[i] = notes[i] xor (1 shl digit)
        return true
    }

    fun erase(i: Int): Boolean {
        if (isSolved || isGiven(i) || (values[i] == 0 && notes[i] == 0)) return false
        snapshot()
        values[i] = 0
        notes[i] = 0
        return true
    }

    /**
     * Reveals the correct digit of [preferred] if that cell is not yet right,
     * otherwise of a random unsolved cell. Returns the revealed cell, or -1.
     */
    fun hint(preferred: Int): Int {
        if (isSolved) return -1
        val i = if (preferred >= 0 && values[preferred] != solution[preferred]) {
            preferred
        } else {
            (0 until Sudoku.CELLS).filter { values[it] != solution[it] }.random()
        }
        snapshot()
        place(i, solution[i])
        return i
    }

    fun undo(): Boolean {
        val state = undoStack.removeLastOrNull() ?: return false
        state.copyInto(values, 0, 0, Sudoku.CELLS)
        state.copyInto(notes, 0, Sudoku.CELLS, 2 * Sudoku.CELLS)
        return true
    }

    private fun place(i: Int, digit: Int) {
        values[i] = digit
        notes[i] = 0
        if (digit == solution[i]) {
            val bit = 1 shl digit
            for (peer in Sudoku.peers[i]) notes[peer] = notes[peer] and bit.inv()
        }
    }

    private fun snapshot() {
        undoStack.addLast(values + notes)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun encode(): String = listOf(
        difficulty.name,
        mistakes,
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
            val parts = data.split(';')
            require(parts.size == 7)
            val game = Game(Difficulty.valueOf(parts[0]), digits(parts[3]), digits(parts[4]))
            game.mistakes = parts[1].toInt()
            game.elapsedSeconds = parts[2].toInt()
            digits(parts[5]).copyInto(game.values)
            val notes = parts[6].split(',').map { it.toInt() }
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
