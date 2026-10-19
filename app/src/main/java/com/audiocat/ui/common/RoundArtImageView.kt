package com.audiocat.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.audiocat.R
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.Shapeable

/**
 * Rounds an image view's drawing instead of masking its drawable.
 *
 * Material's ShapeableImageView only clips what it is given, so a placeholder
 * that carries its own colours - the default cover plate, a tinted glyph - keeps
 * its square corners and pokes out of the rounded frame. Clipping the canvas
 * after the drawable and the tint have been painted makes every artwork surface
 * agree, whatever is drawn into it.
 *
 * The radius is one absolute dp value everywhere on purpose: 3dp reads as the same
 * decision on a 32dp lane thumbnail and on the full-width player plate, which is
 * what the flat 0dp plates and perfect circles elsewhere in the app are aiming at.
 */
class RoundArtImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr), Shapeable {

    private val cornerPath = Path()
    private val bounds = RectF()
    private val radiusPx = RADIUS_DP * context.resources.displayMetrics.density

    private val keepSquare: Boolean = attrs?.let {
        val array = context.obtainStyledAttributes(it, R.styleable.RoundArtImageView)
        val square = array.getBoolean(R.styleable.RoundArtImageView_artKeepSquare, false)
        array.recycle()
        square
    } ?: false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (keepSquare) setMeasuredDimension(measuredWidth, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        cornerPath.reset()
        cornerPath.addRoundRect(bounds, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(cornerPath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    /**
     * Present for the same reason as [ShapeableImageView]: a layout that shares
     * this id between a plain ImageView and this one must still inflate, and
     * Material's shape attributes must parse.
     */
    override fun getShapeAppearanceModel(): ShapeAppearanceModel =
        ShapeAppearanceModel.builder().setAllCornerSizes(radiusPx).build()

    override fun setShapeAppearanceModel(shapeAppearanceModel: ShapeAppearanceModel) = Unit

    private companion object {
        const val RADIUS_DP = 8f
    }
}
