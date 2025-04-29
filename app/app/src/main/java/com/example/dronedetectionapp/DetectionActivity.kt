package com.example.dronedetectionapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<DetectionResult> = emptyList()

    data class DetectionResult(
        val boundingBox: List<Float>,
        val score: Float,
        val label: String?
    )

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 36f
        style = Paint.Style.FILL
    }

    fun setDetections(detections: List<DetectionResult>) {
        this.detections = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (d in detections) {
            val (left, top, right, bottom) = d.boundingBox

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val labelText = "${d.label ?: "Unknown"} ${(d.score * 100).toInt()}%"
            canvas.drawText(labelText, left + 10f, top - 10f, textPaint)
        }
    }
}
