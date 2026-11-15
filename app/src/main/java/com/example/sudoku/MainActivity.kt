package com.example.sudoku

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var board: BoardView
    private lateinit var txtDifficulty: TextView
    private lateinit var txtTime: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnNotes: Button
    private lateinit var btnLock: Button
    private val numberButtons = ArrayList<Button>()
    private val numberBackgrounds = ArrayList<Drawable?>()
    private lateinit var lockedBackground: Drawable

    private var game: Game? = null
    private var notesMode = false

    // In lock mode a number button picks the digit and tapping an empty cell fills it in.
    private var lockMode = false
    private var lockedDigit = 0
    private var generating = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            game?.let {
                if (!generating && !it.isSolved) {
                    it.elapsedSeconds++
                    txtTime.text = formatTime(it.elapsedSeconds)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        board = findViewById(R.id.board)
        txtDifficulty = findViewById(R.id.txtDifficulty)
        txtTime = findViewById(R.id.txtTime)
        btnUndo = findViewById(R.id.btnUndo)
        btnNotes = findViewById(R.id.btnNotes)
        btnLock = findViewById(R.id.btnLock)
        lockedBackground = getDrawable(R.drawable.bg_locked_digit)!!
        buildNumpad()

        board.onCellSelected = { refresh() }
        board.onCellTapped = { onCellTapped(it) }
        // Sliding across the board keeps writing the locked digit into the empty cells it passes.
        board.onCellDragged = { fillLocked(it) }
        findViewById<Button>(R.id.btnNew).setOnClickListener { chooseDifficulty() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { showImportDialog() }
        btnUndo.setOnClickListener { if (game?.undo() == true) refresh() }
        findViewById<Button>(R.id.btnErase).setOnClickListener {
            if (board.selected >= 0 && game?.erase(board.selected) == true) refresh()
        }
        btnNotes.setOnClickListener {
            notesMode = !notesMode
            refresh()
        }
        btnLock.setOnClickListener {
            lockMode = !lockMode
            lockedDigit = 0
            refresh()
        }

        val saved = getPreferences(MODE_PRIVATE).getString(KEY_GAME, null)?.let(Game::decode)
        if (saved != null) {
            game = saved
            board.game = saved
            refresh()
        } else {
            startNewGame(Difficulty.MEDIUM)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(tick, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        game?.let { getPreferences(MODE_PRIVATE).edit().putString(KEY_GAME, it.encode()).apply() }
    }

    private fun buildNumpad() {
        val numpad = findViewById<LinearLayout>(R.id.numpad)
        val height = (56 * resources.displayMetrics.density).toInt()
        for (digit in 1..9) {
            val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = digit.toString()
                textSize = 26f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setTextColor(getColor(R.color.accent))
                setOnClickListener { onDigit(digit) }
            }
            numpad.addView(button, LinearLayout.LayoutParams(0, height, 1f))
            numberButtons += button
            numberBackgrounds += button.background
        }
    }

    private fun onDigit(digit: Int) {
        val g = game ?: return
        if (generating) return
        if (lockMode) {
            lockedDigit = if (lockedDigit == digit) 0 else digit
            refresh()
            return
        }
        val cell = board.selected
        if (cell < 0) return
        place(g, cell, digit)
    }

    private fun onCellTapped(cell: Int) {
        val g = game ?: return
        if (generating || !lockMode) return
        if (g.isGiven(cell)) {
            // Tapping one of the puzzle's own digits locks onto that digit.
            lockedDigit = g.values[cell]
            refresh()
        } else {
            fillLocked(cell)
        }
    }

    /** In lock mode, writes the locked digit into [cell] if it is empty. */
    private fun fillLocked(cell: Int) {
        val g = game ?: return
        if (generating || lockedDigit == 0 || g.values[cell] != 0) return
        place(g, cell, lockedDigit)
    }

    private fun place(g: Game, cell: Int, digit: Int) {
        val changed = if (notesMode) g.toggleNote(cell, digit) else g.enter(cell, digit)
        if (!changed) return
        refresh()
        if (g.isSolved) {
            showSolved(g)
        } else if (g.isFull) {
            // Mistakes are never pointed out while playing, so say so once nothing is left to fill.
            Toast.makeText(this, R.string.full_but_wrong, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseDifficulty() {
        val labels = Difficulty.generated.map { difficultyLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_difficulty)
            .setItems(labels) { _, which -> startNewGame(Difficulty.generated[which]) }
            .show()
    }

    private fun showImportDialog() {
        if (generating) return
        val view = layoutInflater.inflate(R.layout.dialog_import, null)
        val input = view.findViewById<EditText>(R.id.editPuzzle)
        val error = view.findViewById<TextView>(R.id.txtImportError)
        view.findViewById<Button>(R.id.btnCopyPrompt).setOnClickListener { copyPrompt() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setView(view)
            .setPositiveButton(R.string.import_start, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        // Set after show() so that an invalid puzzle does not dismiss the dialog.
        val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        start.setOnClickListener {
            val text = input.text.toString()
            val message = importFormatError(text)
            if (message != null) {
                error.text = message
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val givens = Sudoku.parse(text)!!
            start.isEnabled = false
            error.setText(R.string.import_checking)
            error.visibility = View.VISIBLE
            Thread {
                val solution = Sudoku.solve(givens)
                val unique = solution != null && Sudoku.countSolutions(givens, 2) == 1
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (solution != null && unique) {
                        dialog.dismiss()
                        beginGame(Game(Difficulty.CUSTOM, solution, givens))
                    } else {
                        start.isEnabled = true
                        error.setText(
                            if (solution == null) R.string.import_error_no_solution
                            else R.string.import_error_multiple
                        )
                    }
                }
            }.start()
        }
    }

    /** Describes what is wrong with the typed puzzle, or returns null if it can be checked further. */
    private fun importFormatError(text: String): String? {
        val grid = Sudoku.parse(text)
            ?: return getString(R.string.import_error_length, text.count { it in '0'..'9' })
        if (grid.none { it == 0 }) return getString(R.string.import_error_full)
        val conflict = (0 until Sudoku.CELLS).firstOrNull { i ->
            grid[i] != 0 && Sudoku.peers[i].any { grid[it] == grid[i] }
        } ?: return null
        return getString(R.string.import_error_conflict, conflict / 9 + 1, conflict % 9 + 1, grid[conflict])
    }

    private fun copyPrompt() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_prompt), getString(R.string.ai_prompt)))
        // Android 13+ shows its own confirmation when the clipboard changes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.prompt_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginGame(g: Game) {
        game = g
        board.game = g
        board.selected = -1
        refresh()
    }

    private fun startNewGame(difficulty: Difficulty) {
        if (generating) return
        generating = true
        board.isEnabled = false
        txtDifficulty.setText(R.string.generating)
        Thread {
            val puzzle = Sudoku.generate(difficulty)
            runOnUiThread {
                generating = false
                if (isDestroyed) return@runOnUiThread
                board.isEnabled = true
                beginGame(Game(difficulty, puzzle.solution, puzzle.givens))
            }
        }.start()
    }

    private fun showSolved(g: Game) {
        AlertDialog.Builder(this)
            .setTitle(R.string.solved_title)
            .setMessage(
                getString(
                    R.string.solved_message,
                    difficultyLabel(g.difficulty),
                    formatTime(g.elapsedSeconds),
                )
            )
            .setPositiveButton(R.string.new_game) { _, _ -> chooseDifficulty() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun refresh() {
        val g = game ?: return
        // A digit that is complete can no longer be placed, so it can't stay locked.
        if (lockedDigit != 0 && g.countOf(lockedDigit) >= 9) lockedDigit = 0
        board.highlightDigit = lockedDigit
        board.invalidate()
        btnLock.setText(if (lockMode) R.string.lock_on else R.string.lock_off)
        btnLock.setTextColor(getColor(if (lockMode) R.color.accent else R.color.text_primary))
        txtDifficulty.text = difficultyLabel(g.difficulty)
        txtTime.text = formatTime(g.elapsedSeconds)
        btnUndo.isEnabled = g.canUndo
        btnUndo.alpha = if (g.canUndo) 1f else 0.4f
        btnNotes.setText(if (notesMode) R.string.notes_on else R.string.notes_off)
        btnNotes.setTextColor(getColor(if (notesMode) R.color.accent else R.color.text_primary))
        for ((index, button) in numberButtons.withIndex()) {
            // A digit that is already on the board nine times can't be placed again.
            val available = g.countOf(index + 1) < 9
            button.isEnabled = available
            button.alpha = if (available) 1f else 0.25f
            button.background = if (index + 1 == lockedDigit) lockedBackground else numberBackgrounds[index]
        }
    }

    private fun difficultyLabel(difficulty: Difficulty) = getString(
        when (difficulty) {
            Difficulty.EASY -> R.string.easy
            Difficulty.MEDIUM -> R.string.medium
            Difficulty.HARD -> R.string.hard
            Difficulty.CUSTOM -> R.string.custom
        }
    )

    private fun formatTime(seconds: Int) =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private companion object {
        const val KEY_GAME = "game"
    }
}
