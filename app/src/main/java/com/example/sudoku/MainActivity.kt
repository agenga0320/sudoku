package com.example.sudoku

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
    private lateinit var txtMistakes: TextView
    private lateinit var txtTime: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnNotes: Button
    private val numberButtons = ArrayList<Button>()

    private var game: Game? = null
    private var notesMode = false
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
        txtMistakes = findViewById(R.id.txtMistakes)
        txtTime = findViewById(R.id.txtTime)
        btnUndo = findViewById(R.id.btnUndo)
        btnNotes = findViewById(R.id.btnNotes)
        buildNumpad()

        board.onCellSelected = { refresh() }
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
        findViewById<Button>(R.id.btnHint).setOnClickListener { onHint() }

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
        }
    }

    private fun onDigit(digit: Int) {
        val g = game ?: return
        val cell = board.selected
        if (generating || cell < 0) return
        val changed = if (notesMode) g.toggleNote(cell, digit) else g.enter(cell, digit)
        if (!changed) return
        refresh()
        if (g.isSolved) showSolved(g)
    }

    private fun onHint() {
        val g = game ?: return
        if (generating) return
        val cell = g.hint(board.selected)
        if (cell < 0) return
        board.selected = cell
        refresh()
        if (g.isSolved) showSolved(g)
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
                    g.mistakes,
                )
            )
            .setPositiveButton(R.string.new_game) { _, _ -> chooseDifficulty() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun refresh() {
        val g = game ?: return
        board.invalidate()
        txtDifficulty.text = difficultyLabel(g.difficulty)
        txtMistakes.text = getString(R.string.mistakes, g.mistakes)
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
