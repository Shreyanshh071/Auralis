package com.auralis.music.ui.lyrics.wavy

import androidx.annotation.FloatRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Default values for [CircularWavyProgressIndicator] matching AndroidX Material 3 Expressive.
 */
object WavyProgressIndicatorDefaults {
    val indicatorColor: Color = Color.White
    val trackColor: Color = Color.White.copy(alpha = 0.2f)

    // Standard lyrics indicator styling (Auralis Default, Apple Music, Fade, Glow, etc.)
    val StandardIndicatorSize: Dp = 34.dp
    val StandardStrokeWidth: Dp = 3.0.dp
    val StandardTrackGapSize: Dp = 3.0.dp

    // MetroLyrics indicator styling (Metrolist)
    val MetroIndicatorSize: Dp = 40.dp
    val MetroStrokeWidth: Dp = 5.0.dp
    val MetroTrackGapSize: Dp = 4.0.dp

    val CircularIndicatorStrokeWidth: Dp = 5.dp
    val CircularTrackStrokeWidth: Dp = 5.dp

    val circularIndicatorStroke: Stroke
        @Composable
        get() = Stroke(
            width = with(LocalDensity.current) { CircularIndicatorStrokeWidth.toPx() },
            cap = StrokeCap.Round
        )

    val circularTrackStroke: Stroke
        @Composable
        get() = Stroke(
            width = with(LocalDensity.current) { CircularTrackStrokeWidth.toPx() },
            cap = StrokeCap.Round
        )

    val CircularContainerSize: Dp = 48.dp
    val CircularWavelength: Dp = 15.dp
    val CircularIndicatorTrackGapSize: Dp = 4.dp

    /**
     * Default wave amplitude factor (1.0f) matching ViVi / Material 3 Expressive.
     */
    const val DefaultWaveAmplitude: Float = 1.0f

    val indicatorAmplitude: (progress: Float) -> Float = { progress ->
        if (progress <= 0.1f || progress >= 0.95f) {
            0f
        } else {
            DefaultWaveAmplitude
        }
    }
}

private val IncreasingAmplitudeAnimationSpec: AnimationSpec<Float> =
    tween(
        durationMillis = 500,
        easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f),
    )

private val DecreasingAmplitudeAnimationSpec: AnimationSpec<Float> =
    tween(
        durationMillis = 500,
        easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f),
    )

private const val MinCircularVertexCount = 5
private const val MinAnimationDuration = 50

/**
 * A class that returns and caches the [RoundedPolygon]s and the [Morph] that are displayed by
 * circular wavy progress indicators.
 *
 * Sourced directly from AndroidX Material 3 internal CircularShapes.
 */
private class CircularShapes {
    private var currentSize: Size? = null
    private var currentWavelength: Float = -1f

    private var trackPolygon: RoundedPolygon? = null
    private var activeIndicatorPolygon: RoundedPolygon? = null
    private var activeIndicatorMorph: Morph? = null

    val currentVertexCount = mutableIntStateOf(-1)

    fun update(
        size: Size,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0, fromInclusive = false) strokeWidth: Float,
        requiresMorph: Boolean,
    ) {
        require(wavelength > 0f) { "Wavelength should be greater than zero" }
        if (size == currentSize && wavelength == currentWavelength) {
            if (requiresMorph && activeIndicatorMorph == null) {
                activeIndicatorMorph = Morph(start = trackPolygon!!, end = activeIndicatorPolygon!!)
            }
            return
        }

        val r = size.minDimension / 2 - strokeWidth / 2
        val numVertices = max(MinCircularVertexCount, (2 * PI * r / wavelength).fastRoundToInt())

        if (numVertices != currentVertexCount.intValue) {
            trackPolygon = RoundedPolygon.circle(numVertices = numVertices).normalized()
            activeIndicatorPolygon =
                RoundedPolygon.star(
                    numVerticesPerRadius = numVertices,
                    innerRadius = 0.75f,
                    rounding = CornerRounding(radius = 0.35f, smoothing = 0.4f),
                    innerRounding = CornerRounding(radius = 0.5f),
                ).normalized()
            activeIndicatorMorph = Morph(start = trackPolygon!!, end = activeIndicatorPolygon!!)
        }

        currentSize = size
        currentWavelength = wavelength
        currentVertexCount.intValue = numVertices
    }

    fun getTrackPath(path: Path): Path {
        trackPolygon?.toPath(path = path)
        return path
    }

    fun getProgressPath(
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        path: Path,
        repeatPath: Boolean,
        rotationPivotX: Float = 0.5f,
        rotationPivotY: Float = 0.5f,
    ): Path {
        if (activeIndicatorMorph != null) {
            activeIndicatorMorph!!.toPath(
                progress = amplitude,
                path = path,
                repeatPath = repeatPath,
                rotationPivotX = rotationPivotX,
                rotationPivotY = rotationPivotY,
            )
        } else if (amplitude >= 1f && activeIndicatorPolygon != null) {
            activeIndicatorPolygon!!.toPath(path = path, repeatPath = repeatPath)
        } else {
            trackPolygon?.toPath(path = path, repeatPath = repeatPath)
        }
        return path
    }
}

/**
 * Caches and updates the drawing paths for the circular wavy indicator.
 *
 * Sourced directly from AndroidX Material 3 internal CircularProgressDrawingCache.
 */
private class CircularProgressDrawingCache {
    private var currentSize: Size = Size.Unspecified
    private var currentAmplitude: Float = -1f
    private var currentWavelength: Float = -1f
    private var currentStartProgress = 0f
    private var currentEndProgress = 0f
    private var currentIndicatorTrackGapSize = 0f
    private var currentWaveOffset = -1f
    private var currentStroke = Stroke()
    private var currentTrackStroke = currentStroke

    private var progressPathLength = 0f
    private var trackPathLength = 0f
    private var currentProgressMotionEnabled = false

    private val scaleMatrix = Matrix()
    private val transformMatrix = Matrix()

    val fullProgressPath: Path = Path()
    val fullTrackPath: Path = Path()
    val progressPathToDraw: Path = Path()
    val trackPathToDraw: Path = Path()
    val progressPathMeasure: PathMeasure = PathMeasure()
    val trackPathMeasure: PathMeasure = PathMeasure()

    var currentStrokeCapWidth = 0f

    fun updatePaths(
        size: Size,
        progressPathProvider: (amplitude: Float, wavelength: Float, strokeWidth: Float, size: Size, supportsMotion: Boolean, path: Path) -> Path,
        trackPathProvider: (amplitude: Float, wavelength: Float, strokeWidth: Float, size: Size, path: Path) -> Path?,
        enableProgressMotion: Boolean,
        @FloatRange(from = 0.0, to = 1.0) startProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) endProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ) {
        val pathsUpdates =
            updateFullPaths(
                size = size,
                progressPathProvider = progressPathProvider,
                trackPathProvider = trackPathProvider,
                enableProgressMotion = enableProgressMotion,
                amplitude = amplitude,
                wavelength = wavelength,
                gapSize = gapSize,
                stroke = stroke,
                trackStroke = trackStroke,
            )
        updateDrawPaths(
            forceUpdate = pathsUpdates,
            startProgress = startProgress,
            endProgress = endProgress,
            waveOffset = waveOffset,
        )
    }

    private fun updateFullPaths(
        size: Size,
        progressPathProvider: (amplitude: Float, wavelength: Float, strokeWidth: Float, size: Size, supportsMotion: Boolean, path: Path) -> Path,
        trackPathProvider: (amplitude: Float, wavelength: Float, strokeWidth: Float, size: Size, path: Path) -> Path?,
        enableProgressMotion: Boolean,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ): Boolean {
        if (
            currentSize == size &&
            currentAmplitude == amplitude &&
            currentWavelength == wavelength &&
            currentStroke == stroke &&
            currentTrackStroke == trackStroke &&
            currentIndicatorTrackGapSize == gapSize &&
            currentProgressMotionEnabled == enableProgressMotion
        ) {
            return false
        }

        val height = size.height
        val width = size.width

        currentStrokeCapWidth =
            if (
                (stroke.cap == StrokeCap.Butt && trackStroke.cap == StrokeCap.Butt) ||
                height > width
            ) {
                0f
            } else {
                max(stroke.width / 2, trackStroke.width / 2)
            }

        scaleMatrix.reset()
        scaleMatrix.apply { scale(x = width - stroke.width, y = height - stroke.width) }

        fullProgressPath.rewind()
        progressPathProvider(
            amplitude,
            wavelength,
            stroke.width,
            size,
            enableProgressMotion,
            fullProgressPath,
        )
        processPath(fullProgressPath, size, scaleMatrix)
        progressPathMeasure.setPath(path = fullProgressPath, forceClosed = true)
        progressPathLength =
            if (enableProgressMotion) {
                progressPathMeasure.length / 2
            } else {
                progressPathMeasure.length
            }

        fullTrackPath.rewind()
        val trackPathForAmplitude =
            trackPathProvider(amplitude, wavelength, stroke.width, size, fullTrackPath)
        if (trackPathForAmplitude != null) {
            processPath(fullTrackPath, size, scaleMatrix)
            trackPathMeasure.setPath(path = fullTrackPath, forceClosed = true)
            trackPathLength = trackPathMeasure.length
        } else {
            trackPathLength = 0f
        }

        currentSize = size
        currentAmplitude = amplitude
        currentWavelength = wavelength
        currentStroke = stroke
        currentTrackStroke = trackStroke
        currentIndicatorTrackGapSize = gapSize
        currentProgressMotionEnabled = enableProgressMotion

        return true
    }

    private fun processPath(path: Path, size: Size, scaleMatrix: Matrix) {
        path.transform(scaleMatrix)
        val progressPathBounds = path.getBounds()
        path.translate(size.center - progressPathBounds.center)
    }

    private fun updateDrawPaths(
        forceUpdate: Boolean,
        @FloatRange(from = 0.0, to = 1.0) startProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) endProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
    ) {
        require(currentSize != Size.Unspecified) {
            "updateDrawPaths was called before updateFullPaths"
        }
        if (
            !forceUpdate &&
            currentStartProgress == startProgress &&
            currentEndProgress == endProgress &&
            currentWaveOffset == waveOffset
        ) {
            return
        }

        trackPathToDraw.rewind()
        progressPathToDraw.rewind()

        val pStart = startProgress * progressPathLength
        val pStop = endProgress * progressPathLength

        val trackGapSize = min(pStop, currentIndicatorTrackGapSize)
        val horizontalInsets = min(pStop, currentStrokeCapWidth)
        val trackSpacing = horizontalInsets * 2 + trackGapSize

        if (currentProgressMotionEnabled) {
            val coercedWaveOffset = waveOffset.fastCoerceIn(0f, 1f)
            val startStopShift = coercedWaveOffset * progressPathLength

            progressPathMeasure.getSegment(
                startDistance = pStart + startStopShift,
                stopDistance = pStop + startStopShift,
                destination = progressPathToDraw,
            )

            val offsetAngle = coercedWaveOffset * 360 % 360
            if (offsetAngle != 0f) {
                val fullProgressBounds = fullProgressPath.getBounds()
                progressPathToDraw.translate(
                    Offset(-fullProgressBounds.center.x, -fullProgressBounds.center.y)
                )
                transformMatrix.reset()
                progressPathToDraw.transform(
                    transformMatrix.apply { rotateZ(degrees = -offsetAngle) }
                )
                progressPathToDraw.translate(
                    Offset(fullProgressBounds.center.x, fullProgressBounds.center.y)
                )
            }
        } else {
            progressPathMeasure.getSegment(
                startDistance = pStart,
                stopDistance = pStop,
                destination = progressPathToDraw,
            )
        }

        if (trackPathLength > 0) {
            val tStart = endProgress * trackPathLength + trackSpacing
            val tStop = trackPathLength - trackSpacing
            if (tStop > tStart) {
                trackPathMeasure.getSegment(
                    startDistance = tStart,
                    stopDistance = tStop,
                    destination = trackPathToDraw,
                )
            }
        }

        currentStartProgress = startProgress
        currentEndProgress = endProgress
        currentWaveOffset = waveOffset
    }
}

/** Base modifier node for circular wavy progress */
private abstract class BaseCircularWavyProgressNode(
    colorParameter: Color,
    trackColorParameter: Color,
    strokeParameter: Stroke,
    trackStrokeParameter: Stroke,
    gapSizeParameter: Dp,
    wavelengthParameter: Dp,
    waveSpeedParameter: Dp,
) : DelegatingNode() {

    var color: Color = colorParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDraw()
            }
        }

    var trackColor: Color = trackColorParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDraw()
            }
        }

    var stroke: Stroke = strokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var trackStroke: Stroke = trackStrokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var gapSize: Dp = gapSizeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var wavelength: Dp = wavelengthParameter
        set(value) {
            if (field != value) {
                field = value
                startOffsetAnimation()
            }
        }

    var waveSpeed: Dp = waveSpeedParameter
        set(value) {
            if (field != value) {
                field = value
                startOffsetAnimation()
            }
        }

    protected abstract fun invalidateDraw()
    protected abstract fun invalidateDrawCache()
    protected abstract fun isDrawingWave(): Boolean

    protected val circularShapes = CircularShapes()
    protected val progressDrawingCache = CircularProgressDrawingCache()

    protected val waveOffsetState = mutableFloatStateOf(0f)
    protected var offsetAnimatable: Animatable<Float, AnimationVector1D>? = null
    protected var offsetAnimationJob: Job? = null

    protected var vertexCountForCurrentAnimation = -1

    protected fun trackPathProvider(
        amplitude: Float,
        wavelength: Float,
        strokeWidth: Float,
        size: Size,
        path: Path,
    ): Path? = circularShapes.getTrackPath(path = path)

    protected fun progressPathProvider(
        amplitude: Float,
        wavelength: Float,
        strokeWidth: Float,
        size: Size,
        supportMotion: Boolean,
        path: Path,
    ): Path =
        circularShapes.getProgressPath(
            amplitude = amplitude,
            path = path,
            repeatPath = supportMotion,
        )

    protected fun startOffsetAnimation() {
        stopOffsetAnimation()
        if (!isAttached || !coroutineScope.isActive || !isDrawingWave()) return

        if (waveSpeed > 0.dp && wavelength > 0.dp && vertexCountForCurrentAnimation > 0) {
            val durationMillis =
                ((wavelength / waveSpeed) * 1000 * vertexCountForCurrentAnimation)
                    .fastRoundToInt()
                    .coerceAtLeast(MinAnimationDuration)

            val startOffset = waveOffsetState.floatValue
            offsetAnimatable = Animatable(startOffset)
            offsetAnimationJob = coroutineScope.launch {
                val anim = offsetAnimatable ?: return@launch
                anim.updateBounds(startOffset, startOffset + 1f)
                anim.animateTo(
                    targetValue = startOffset + 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                ) {
                    waveOffsetState.floatValue = value % 1f
                }
            }
        } else {
            waveOffsetState.floatValue = 0f
        }
    }

    protected fun stopOffsetAnimation() {
        offsetAnimationJob?.cancel()
        offsetAnimationJob = null
        offsetAnimatable = null
    }
}

/** Node for determinate circular wavy progress */
private class DeterminateCircularWavyProgressNode(
    var progress: () -> Float,
    var amplitude: (progress: Float) -> Float,
    colorParameter: Color,
    trackColorParameter: Color,
    strokeParameter: Stroke,
    trackStrokeParameter: Stroke,
    gapSizeParameter: Dp,
    wavelengthParameter: Dp,
    waveSpeedParameter: Dp,
) : BaseCircularWavyProgressNode(
    colorParameter = colorParameter,
    trackColorParameter = trackColorParameter,
    strokeParameter = strokeParameter,
    trackStrokeParameter = trackStrokeParameter,
    gapSizeParameter = gapSizeParameter,
    wavelengthParameter = wavelengthParameter,
    waveSpeedParameter = waveSpeedParameter,
) {
    private val amplitudeState = mutableFloatStateOf(0f)
    private var amplitudeAnimatable: Animatable<Float, AnimationVector1D>? = null
    private var amplitudeAnimationJob: Job? = null

    override fun onAttach() {}

    override fun onDetach() {
        super.onDetach()
        amplitudeAnimatable = null
        vertexCountForCurrentAnimation = -1
    }

    override fun invalidateDraw() {
        cacheDrawNode.invalidateDraw()
    }

    override fun invalidateDrawCache() {
        cacheDrawNode.invalidateDrawCache()
    }

    override fun isDrawingWave() = amplitudeState.floatValue > 0f || (amplitudeAnimatable?.targetValue ?: 0f) > 0f

    val cacheDrawNode =
        delegate(
            CacheDrawModifierNode {
                val currentProgress = progress().fastCoerceIn(0f, 1f)
                val currentGapPx = gapSize.toPx()
                val currentWavelengthPx = wavelength.toPx()
                val enableMotion = waveSpeed > 0.dp

                val targetAmplitude = amplitude(currentProgress).fastCoerceIn(0f, 1f)

                amplitudeAnimatable
                    ?: Animatable(initialValue = targetAmplitude).also {
                        amplitudeAnimatable = it
                        amplitudeState.floatValue = targetAmplitude
                    }

                if (
                    isAttached &&
                    amplitudeAnimatable!!.targetValue != targetAmplitude &&
                    (amplitudeAnimationJob == null || amplitudeAnimationJob?.isCompleted == true)
                ) {
                    amplitudeAnimationJob = coroutineScope.launch {
                        val anim = amplitudeAnimatable ?: return@launch
                        anim.animateTo(
                            targetValue = targetAmplitude,
                            animationSpec =
                                if (anim.value < targetAmplitude) {
                                    IncreasingAmplitudeAnimationSpec
                                } else {
                                    DecreasingAmplitudeAnimationSpec
                                },
                        ) {
                            amplitudeState.floatValue = value
                            if (value > 0f && (offsetAnimationJob == null || offsetAnimationJob?.isCompleted == true)) {
                                startOffsetAnimation()
                            }
                        }
                        if (targetAmplitude == 0f) {
                            stopOffsetAnimation()
                        }
                    }
                }

                circularShapes.update(
                    size = size,
                    wavelength = currentWavelengthPx,
                    strokeWidth = stroke.width,
                    requiresMorph = amplitudeAnimationJob != null,
                )

                if (vertexCountForCurrentAnimation != circularShapes.currentVertexCount.intValue) {
                    vertexCountForCurrentAnimation =
                        circularShapes.currentVertexCount.intValue.coerceAtLeast(MinCircularVertexCount)
                }

                if (
                    targetAmplitude > 0 &&
                    (offsetAnimationJob == null || offsetAnimationJob?.isCompleted == true)
                ) {
                    startOffsetAnimation()
                }

                onDrawWithContent {
                    val animatedAmplitudeValue = amplitudeState.floatValue
                    val liveProgress = progress().fastCoerceIn(0f, 1f)
                    progressDrawingCache.updatePaths(
                        size = size,
                        progressPathProvider = ::progressPathProvider,
                        trackPathProvider = ::trackPathProvider,
                        enableProgressMotion = enableMotion,
                        startProgress = 0f,
                        endProgress = liveProgress,
                        amplitude = animatedAmplitudeValue,
                        waveOffset =
                            if (animatedAmplitudeValue > 0f && enableMotion) {
                                waveOffsetState.floatValue
                            } else {
                                0f
                            },
                        wavelength = currentWavelengthPx,
                        gapSize = currentGapPx,
                        stroke = stroke,
                        trackStroke = trackStroke,
                    )
                    drawCircularIndicator(
                        color = color,
                        trackColor = trackColor,
                        stroke = stroke,
                        trackStroke = trackStroke,
                        drawingCache = progressDrawingCache,
                    )
                }
            }
        )
}

private fun DrawScope.drawCircularIndicator(
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    trackStroke: Stroke,
    drawingCache: CircularProgressDrawingCache,
) {
    if (trackColor != Color.Transparent && trackColor != Color.Unspecified) {
        drawPath(path = drawingCache.trackPathToDraw, color = trackColor, style = trackStroke)
    }
    if (color != Color.Transparent && color != Color.Unspecified) {
        drawPath(path = drawingCache.progressPathToDraw, color = color, style = stroke)
    }
}

/** Element for determinate circular wavy progress */
private class DeterminateCircularWavyProgressElement(
    val progress: () -> Float,
    val color: Color,
    val trackColor: Color,
    val stroke: Stroke,
    val trackStroke: Stroke,
    val gapSize: Dp,
    val amplitude: (progress: Float) -> Float,
    val wavelength: Dp,
    val waveSpeed: Dp,
) : ModifierNodeElement<DeterminateCircularWavyProgressNode>() {

    override fun create(): DeterminateCircularWavyProgressNode =
        DeterminateCircularWavyProgressNode(
            progress = progress,
            amplitude = amplitude,
            colorParameter = color,
            trackColorParameter = trackColor,
            strokeParameter = stroke,
            trackStrokeParameter = trackStroke,
            gapSizeParameter = gapSize,
            wavelengthParameter = wavelength,
            waveSpeedParameter = waveSpeed,
        )

    override fun update(node: DeterminateCircularWavyProgressNode) {
        node.color = color
        node.trackColor = trackColor
        node.stroke = stroke
        node.trackStroke = trackStroke
        node.gapSize = gapSize
        node.wavelength = wavelength
        node.waveSpeed = waveSpeed
        if (node.progress !== progress || node.amplitude !== amplitude) {
            node.progress = progress
            node.amplitude = amplitude
            node.cacheDrawNode.invalidateDrawCache()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "determinateCircularWavyProgressIndicator"
        properties["color"] = color
        properties["trackColor"] = trackColor
        properties["stroke"] = stroke
        properties["trackStroke"] = trackStroke
        properties["gapSize"] = gapSize
        properties["wavelength"] = wavelength
        properties["waveSpeed"] = waveSpeed
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeterminateCircularWavyProgressElement) return false
        if (color != other.color) return false
        if (trackColor != other.trackColor) return false
        if (stroke != other.stroke) return false
        if (trackStroke != other.trackStroke) return false
        if (gapSize != other.gapSize) return false
        if (wavelength != other.wavelength) return false
        if (waveSpeed != other.waveSpeed) return false
        if (progress !== other.progress || amplitude !== other.amplitude) return false
        return true
    }

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + trackColor.hashCode()
        result = 31 * result + stroke.hashCode()
        result = 31 * result + trackStroke.hashCode()
        result = 31 * result + gapSize.hashCode()
        result = 31 * result + wavelength.hashCode()
        result = 31 * result + waveSpeed.hashCode()
        result = 31 * result + progress.hashCode()
        result = 31 * result + amplitude.hashCode()
        return result
    }
}

/** Modifier extension for circular wavy progress */
fun Modifier.circularWavyProgressIndicator(
    progress: () -> Float,
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    trackStroke: Stroke,
    gapSize: Dp,
    amplitude: (progress: Float) -> Float,
    wavelength: Dp,
    waveSpeed: Dp,
): Modifier =
    this.then(
        DeterminateCircularWavyProgressElement(
            progress = progress,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
            gapSize = gapSize,
            amplitude = amplitude,
            wavelength = wavelength,
            waveSpeed = waveSpeed,
        )
    )

/**
 * Official Material 3 Expressive CircularWavyProgressIndicator ported directly
 * into Auralis, running self-contained on stable Compose without requiring Material 3 1.4 alpha.
 */
@Composable
fun CircularWavyProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    stroke: Stroke = WavyProgressIndicatorDefaults.circularIndicatorStroke,
    trackStroke: Stroke = WavyProgressIndicatorDefaults.circularTrackStroke,
    gapSize: Dp = WavyProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
    amplitude: (progress: Float) -> Float = WavyProgressIndicatorDefaults.indicatorAmplitude,
    wavelength: Dp = WavyProgressIndicatorDefaults.CircularWavelength,
    waveSpeed: Dp = wavelength,
) {
    Spacer(
        modifier =
            modifier
                .size(WavyProgressIndicatorDefaults.CircularContainerSize)
                .circularWavyProgressIndicator(
                    progress = progress,
                    color = color,
                    trackColor = trackColor,
                    stroke = stroke,
                    trackStroke = trackStroke,
                    gapSize = gapSize,
                    amplitude = amplitude,
                    wavelength = wavelength,
                    waveSpeed = waveSpeed,
                )
                .semantics(mergeDescendants = true) {
                    val progressValue = progress()
                    val clampedProgress = progressValue.coerceIn(0f, 1f)
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = if (clampedProgress.isNaN()) 0f else clampedProgress,
                            range = 0f..1f,
                        )
                }
    )
}

/**
 * Backwards-compatible overload and convenience composable for Auralis lyrics and intros.
 */
@Composable
fun WavyProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    strokeWidth: Dp = 3.0.dp,
    gapSize: Dp = 3.0.dp,
    @Suppress("UNUSED_PARAMETER") lobes: Int = 7,
    @Suppress("UNUSED_PARAMETER") amplitudeRatio: Float = 0.055f,
    amplitude: (progress: Float) -> Float = { 1f }, // Continuous active fluid motion
    wavelength: Dp = WavyProgressIndicatorDefaults.CircularWavelength,
    waveSpeed: Dp = wavelength,
) {
    val strokeWidthPx = with(LocalDensity.current) { strokeWidth.coerceAtLeast(1.dp).toPx() }
    val indicatorStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    CircularWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        stroke = indicatorStroke,
        trackStroke = indicatorStroke,
        gapSize = gapSize,
        amplitude = amplitude,
        wavelength = wavelength,
        waveSpeed = waveSpeed,
    )
}
