package com.example.sudoku

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var game: Game? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Index of the selected cell, or -1. */
    var selected = -1
        set(value) {
            field = value
            invalidate()
        }

    /** Digit whose cells are highlighted instead of the selected cell's digit; 0 for none. */
    var highlightDigit = 0
        set(value) {
            field = value
            invalidate()
        }

    var onCellSelected: ((Int) -> Unit)? = null

    /** Called for every touch down on a cell, even one that is already selected. */
    var onCellTapped: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val thick = 2.5f * density
    private val thin = 1f * density

    private val fillPaint = Paint()
    private val thinPaint = Paint().apply {
        color = context.getColor(R.color.line_thin)
        strokeWidth = thin
    }
    private val thickPaint = Paint().apply {
        color = context.getColor(R.color.line_thick)
        strokeWidth = thick
        strokeCap = Paint.Cap.SQUARE
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val boardColor = context.getColor(R.color.board_bg)
    private val selectedColor = context.getColor(R.color.cell_selected)
    private val sameColor = context.getColor(R.color.cell_same)
    private val peerColor = context.getColor(R.color.cell_peer)
    private val givenColor = context.getColor(R.color.digit_given)
    private val userColor = context.getColor(R.color.digit_user)
    private val noteColor = context.getColor(R.color.note)

    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    // The grid is inset by half the border stroke so the outer lines are not clipped.
    private val origin get() = thick / 2
    private val cellSize get() = (width - thick) / 9f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val o = origin
        val cell = cellSize
        val end = o + cell * 9

        fillPaint.color = boardColor
        canvas.drawRect(o, o, end, end, fillPaint)

        game?.let { g ->
            drawHighlights(canvas, g)
            drawDigits(canvas, g)
        }

        for (k in 0..9) {
            val p = o + k * cell
            val paint = if (k % 3 == 0) thickPaint else thinPaint
            canvas.drawLine(p, o, p, end, paint)
            canvas.drawLine(o, p, end, p, paint)
        }
    }

    /** The digit being emphasised: the locked digit, else the selected cell's digit; 0 for none. */
    private fun activeDigit(g: Game) = when {
        highlightDigit != 0 -> highlightDigit
        selected >= 0 -> g.values[selected]
        else -> 0
    }

    private fun drawHighlights(canvas: Canvas, g: Game) {
        val digit = activeDigit(g)
        val peers = if (selected >= 0) Sudoku.peers[selected] else null
        if (digit == 0 && peers == null) return
        for (i in 0 until Sudoku.CELLS) {
            fillPaint.color = when {
                i == selected -> selectedColor
                digit != 0 && g.values[i] == digit -> sameColor
                peers != null && i in peers -> peerColor
                else -> continue
            }
            val left = origin + (i % 9) * cellSize
            val top = origin + (i / 9) * cellSize
            canvas.drawRect(left, top, left + cellSize, top + cellSize, fillPaint)
        }
    }

    private fun drawDigits(canvas: Canvas, g: Game) {
        val cell = cellSize
        digitPaint.textSize = cell * 0.62f
        notePaint.textSize = cell * 0.26f
        val digitOffset = -(digitPaint.ascent() + digitPaint.descent()) / 2
        val noteOffset = -(notePaint.ascent() + notePaint.descent()) / 2
        val active = activeDigit(g)

        for (i in 0 until Sudoku.CELLS) {
            val left = origin + (i % 9) * cell
            val top = origin + (i / 9) * cell
            val value = g.values[i]
            if (value != 0) {
                digitPaint.color = if (g.isGiven(i)) givenColor else userColor
                digitPaint.typeface = if (g.isGiven(i)) boldTypeface else Typeface.DEFAULT
                canvas.drawText(value.toString(), left + cell / 2, top + cell / 2 + digitOffset, digitPaint)
            } else if (g.notes[i] != 0) {
                val sub = cell / 3
                for (d in 1..9) {
                    if (g.notes[i] and (1 shl d) == 0) continue
                    val x = left + ((d - 1) % 3 + 0.5f) * sub
                    val y = top + ((d - 1) / 3 + 0.5f) * sub + noteOffset
                    notePaint.color = if (d == active) userColor else noteColor
                    notePaint.typeface = if (d == active) boldTypeface else Typeface.DEFAULT
                    canvas.drawText(d.toString(), x, y, notePaint)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val col = ((event.x - origin) / cellSize).toInt()
            val row = ((event.y - origin) / cellSize).toInt()
            if (col in 0..8 && row in 0..8) {
                val index = row * 9 + col
                if (index != selected) {
                    selected = index
                    onCellSelected?.invoke(index)
                }
                if (event.action == MotionEvent.ACTION_DOWN) onCellTapped?.invoke(index)
            }
        }
        return true
    }
}
