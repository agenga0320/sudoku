package com.example.sudoku

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var board: BoardView
    private lateinit var txtDifficulty: TextView
    private lateinit var txtTime: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnNotes: ImageButton
    private lateinit var btnLock: ImageButton
    private val numberButtons = ArrayList<Button>()
    private val numberBackgrounds = ArrayList<Drawable?>()
    private lateinit var lockedBackground: Drawable

    private var game: Game? = null
    private var notesMode = false

    // In lock mode a number button picks the digit and tapping an empty cell fills it in.
    private var lockMode = false
    private var lockedDigit = 0
    private var generating = false

    // Bumped to discard the answer of a hint request the player cancelled.
    private var hintRequest = 0

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
        btnRedo = findViewById(R.id.btnRedo)
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
        findViewById<Button>(R.id.btnHint).apply {
            setOnClickListener { requestHint() }
            setOnLongClickListener { showKeyDialog(null); true }
        }
        btnUndo.setOnClickListener { if (game?.undo() == true) refresh() }
        btnRedo.setOnClickListener { if (game?.redo() == true) refresh() }
        findViewById<View>(R.id.btnErase).setOnClickListener {
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

        // Only on a fresh start, not when the activity is recreated.
        if (savedInstanceState == null) checkForUpdate()

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
        val digit = g.values[cell]
        when {
            digit == 0 -> fillLocked(cell)
            // A given can't be changed, and in notes mode a placed digit isn't written over,
            // so tapping either (or any digit while none is locked) locks onto that digit.
            g.isGiven(cell) || notesMode || lockedDigit == 0 || digit == lockedDigit -> {
                lockedDigit = digit
                refresh()
            }
            // Outside notes mode, the player's own digit is replaced by the locked one.
            else -> place(g, cell, lockedDigit)
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

    /** Looks for a newer release in the background; stays silent when offline or up to date. */
    private fun checkForUpdate() {
        val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        Thread {
            val release = Update.latest() ?: return@Thread
            if (!Update.isNewer(release.version, installed)) return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_title, release.version))
                    .setMessage(getString(R.string.update_message, installed))
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) }
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            }
        }.start()
    }

    private fun requestHint() {
        val g = game ?: return
        if (generating || g.isSolved || g.isFull) return
        val service = savedService(savedProvider())
        if (service.apiKey.isEmpty()) return showKeyDialog { requestHint() }

        // The player may keep playing behind the dialog, so the request works on a copy.
        val snapshot = Game(g.difficulty, g.solution, g.givens).also { g.values.copyInto(it.values) }
        val selected = board.selected
        val request = ++hintRequest
        val waiting = AlertDialog.Builder(this)
            .setMessage(R.string.hint_thinking)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (request == hintRequest) hintRequest++ }
            .show()
        Thread {
            val result = runCatching { Coach.ask(service, snapshot, selected) }
            runOnUiThread {
                if (isDestroyed || request != hintRequest) return@runOnUiThread
                hintRequest++
                waiting.dismiss()
                result.onSuccess { hint -> if (hint != null) showHint(hint) else alert(getString(R.string.hint_unreliable)) }
                    .onFailure { e ->
                        showAiError(e)
                        if (e is Coach.BadKeyException) showKeyDialog { requestHint() }
                    }
            }
        }.start()
    }

    private fun showAiError(e: Throwable) = when (e) {
        is Coach.BadKeyException -> Toast.makeText(this, R.string.hint_error_key, Toast.LENGTH_LONG).show()
        is IOException -> alert(getString(R.string.hint_error_network))
        else -> alert(getString(R.string.hint_error, e.message ?: e.javaClass.simpleName))
    }

    private fun savedProvider(): Provider {
        val name = getPreferences(MODE_PRIVATE).getString(KEY_PROVIDER, null)
        return Provider.values().firstOrNull { it.name == name } ?: Provider.CLAUDE
    }

    /** The key and model the player saved for [provider]; the key is empty if there is none. */
    private fun savedService(provider: Provider): Coach.Service {
        val prefs = getPreferences(MODE_PRIVATE)
        return Coach.Service(
            provider,
            prefs.getString(KEY_API + provider.name, "").orEmpty(),
            prefs.getString(KEY_MODEL + provider.name, null) ?: provider.defaultModel,
        )
    }

    /** Shows the first hint level; "more" reveals the next one, up to the full reasoning. */
    private fun showHint(hint: Coach.Hint) {
        val labels = listOf(R.string.hint_level_1, R.string.hint_level_2, R.string.hint_level_3)
        val text = { shown: Int -> (0 until shown).joinToString("\n\n") { getString(labels[it], hint.levels[it]) } }
        var shown = 1
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.hint_title)
            .setMessage(text(shown))
            .setPositiveButton(R.string.hint_more, null)
            .setNegativeButton(R.string.close, null)
            .show()
        // Set after show() so that asking for more does not dismiss the dialog.
        val more = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        more.setOnClickListener {
            shown++
            dialog.setMessage(text(shown))
            if (shown == labels.size) more.visibility = View.GONE
        }
    }

    /**
     * Lets the player choose the AI service and enter its API key and model.
     * Each service keeps its own key and model; saving an empty key removes it.
     */
    private fun showKeyDialog(onSaved: (() -> Unit)?) {
        val view = layoutInflater.inflate(R.layout.dialog_api_key, null)
        val spinner = view.findViewById<Spinner>(R.id.spinProvider)
        val inputKey = view.findViewById<EditText>(R.id.editKey)
        val inputModel = view.findViewById<EditText>(R.id.editModel)
        val providers = Provider.values()
        val typed = { Coach.Service(providers[spinner.selectedItemPosition], inputKey.text.toString().trim(), inputModel.text.toString().trim()) }

        spinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label })
        spinner.setSelection(savedProvider().ordinal)
        // Also runs once for the initial selection, which fills in the fields.
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                val saved = savedService(providers[position])
                inputKey.setText(saved.apiKey)
                inputModel.setText(saved.model)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        view.findViewById<Button>(R.id.btnGetKey).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(typed().provider.keyUrl))) }
        }
        view.findViewById<Button>(R.id.btnPickModel).setOnClickListener { pickModel(typed(), inputModel) }

        AlertDialog.Builder(this)
            .setTitle(R.string.key_title)
            .setView(view)
            .setPositiveButton(R.string.key_save) { _, _ ->
                val service = typed()
                val name = service.provider.name
                getPreferences(MODE_PRIVATE).edit()
                    .putString(KEY_PROVIDER, name)
                    .putString(KEY_API + name, service.apiKey)
                    .putString(KEY_MODEL + name, service.model.ifEmpty { service.provider.defaultModel })
                    .apply()
                if (service.apiKey.isNotEmpty()) onSaved?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks the service which models the typed key can use and puts the chosen one in [into]. */
    private fun pickModel(service: Coach.Service, into: EditText) {
        if (service.apiKey.isEmpty()) {
            Toast.makeText(this, R.string.key_needed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.models_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { Coach.models(service) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { models ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.model_pick)
                        .setItems(models.toTypedArray()) { _, which -> into.setText(models[which]) }
                        .show()
                }.onFailure(::showAiError)
            }
        }.start()
    }

    private fun alert(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton(R.string.close, null).show()
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
        setToggle(btnLock, lockMode, R.drawable.ic_lock, R.drawable.ic_lock_off, R.string.lock_on, R.string.lock_off)
        txtDifficulty.text = difficultyLabel(g.difficulty)
        txtTime.text = formatTime(g.elapsedSeconds)
        btnUndo.isEnabled = g.canUndo
        btnUndo.alpha = if (g.canUndo) 1f else 0.4f
        btnRedo.isEnabled = g.canRedo
        btnRedo.alpha = if (g.canRedo) 1f else 0.4f
        setToggle(btnNotes, notesMode, R.drawable.ic_pencil, R.drawable.ic_pen, R.string.notes_on, R.string.notes_off)
        for ((index, button) in numberButtons.withIndex()) {
            // A digit that is already on the board nine times can't be placed again.
            val available = g.countOf(index + 1) < 9
            button.isEnabled = available
            button.alpha = if (available) 1f else 0.25f
            button.background = if (index + 1 == lockedDigit) lockedBackground else numberBackgrounds[index]
        }
    }

    /** Shows a mode button's on or off icon; the label is kept for screen readers. */
    private fun setToggle(button: ImageButton, on: Boolean, iconOn: Int, iconOff: Int, labelOn: Int, labelOff: Int) {
        button.setImageResource(if (on) iconOn else iconOff)
        button.imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.text_primary))
        button.contentDescription = getString(if (on) labelOn else labelOff)
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
        const val KEY_PROVIDER = "ai_provider"

        // Followed by the provider's name: each service keeps its own key and model.
        const val KEY_API = "ai_key_"
        const val KEY_MODEL = "ai_model_"
    }
}
