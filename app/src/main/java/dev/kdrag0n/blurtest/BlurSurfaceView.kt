package dev.kdrag0n.blurtest

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.pow

/**
 * This is an implementation of dual-filtered Kawase blur, as described in here:
 * https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_notes.pdf
 */
class BlurSurfaceView(context: Context, private val bgBitmap: Bitmap, private val noiseBitmap: Bitmap) : GLSurfaceView(context) {
    private val renderer = BlurRenderer()

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
    }

    inner class BlurRenderer : Renderer {
        private var mMeshBuffer = 0

        private var mPassthroughProgram = 0
        private var mPPosLoc = 0
        private var mPUvLoc = 0
        private var mPTextureLoc = 0
        private var mPVertexArray = 0

        private var mMixProgram = 0
        private var mMPosLoc = 0
        private var mMUvLoc = 0
        private var mMCompositionTextureLoc = 0
        private var mMBlurredTextureLoc = 0
        private var mMDitherTextureLoc = 0
        private var mMBlurOpacityLoc = 0
        private var mMVertexArray = 0

        private var mDitherMixProgram = 0
        private var mDMPosLoc = 0
        private var mDMUvLoc = 0
        private var mDMCompositionTextureLoc = 0
        private var mDMBlurredTextureLoc = 0
        private var mDMDitherTextureLoc = 0
        private var mDMBlurOpacityLoc = 0
        private var mDMVertexArray = 0

        private var mDownsampleProgram = 0
        private var mDPosLoc = 0
        private var mDUvLoc = 0
        private var mDTextureLoc = 0
        private var mDHalfPixelLoc = 0
        private var mDVertexArray = 0

        private var mUpsampleProgram = 0
        private var mUPosLoc = 0
        private var mUUvLoc = 0
        private var mUTextureLoc = 0
        private var mUHalfPixelLoc = 0
        private var mUVertexArray = 0

        private lateinit var mBackgroundFbo: GLFramebuffer
        private lateinit var mDitherFbo: GLFramebuffer
        private lateinit var mCompositionFbo: GLFramebuffer
        private lateinit var mPassFbos: List<GLFramebuffer>
        private lateinit var mLastDrawTarget: GLFramebuffer

        private var mRadius = 0
        private var mPasses = 0
        private var mOffset = 1.0f

        private var mWidth = 0
        private var mHeight = 0

        private fun init() {
            val vboData = floatArrayOf(
                // Position                              // UV
                translation - size, -translation - size, 0.0f, 0.0f - translation,
                translation - size, -translation + size, 0.0f, size - translation,
                translation + size, -translation + size, size, size - translation
            )
            mMeshBuffer = GLUtils.createVertexBuffer(vboData)

            mPassthroughProgram = GLUtils.createProgram(VERTEX_SHADER, PASSTHROUGH_FRAG_SHADER)
            mPPosLoc = GLES31.glGetAttribLocation(mPassthroughProgram, "aPosition")
            mPUvLoc = GLES31.glGetAttribLocation(mPassthroughProgram, "aUV")
            mPTextureLoc = GLES31.glGetUniformLocation(mPassthroughProgram, "uTexture")
            mPVertexArray = GLUtils.createVertexArray(mMeshBuffer, mPPosLoc, mPUvLoc)

            mMixProgram = GLUtils.createProgram(VERTEX_SHADER, MIX_FRAG_SHADER)
            mMPosLoc = GLES31.glGetAttribLocation(mMixProgram, "aPosition")
            mMUvLoc = GLES31.glGetAttribLocation(mMixProgram, "aUV")
            mMCompositionTextureLoc = GLES31.glGetUniformLocation(mMixProgram, "uCompositionTexture")
            mMBlurredTextureLoc = GLES31.glGetUniformLocation(mMixProgram, "uBlurredTexture")
            mMDitherTextureLoc = GLES31.glGetUniformLocation(mMixProgram, "uDitherTexture")
            mMBlurOpacityLoc = GLES31.glGetUniformLocation(mMixProgram, "uBlurOpacity")
            mMVertexArray = GLUtils.createVertexArray(mMeshBuffer, mMPosLoc, mMUvLoc)

            mDitherMixProgram = GLUtils.createProgram(VERTEX_SHADER, DITHER_MIX_FRAG_SHADER)
            mDMPosLoc = GLES31.glGetAttribLocation(mDitherMixProgram, "aPosition")
            mDMUvLoc = GLES31.glGetAttribLocation(mDitherMixProgram, "aUV")
            mDMCompositionTextureLoc = GLES31.glGetUniformLocation(mDitherMixProgram, "uCompositionTexture")
            mDMBlurredTextureLoc = GLES31.glGetUniformLocation(mDitherMixProgram, "uBlurredTexture")
            mDMDitherTextureLoc = GLES31.glGetUniformLocation(mDitherMixProgram, "uDitherTexture")
            mDMBlurOpacityLoc = GLES31.glGetUniformLocation(mDitherMixProgram, "uBlurOpacity")
            mDMVertexArray = GLUtils.createVertexArray(mMeshBuffer, mDMPosLoc, mDMUvLoc)

            mDownsampleProgram = GLUtils.createProgram(VERTEX_SHADER, DOWNSAMPLE_FRAG_SHADER)
            mDPosLoc = GLES31.glGetAttribLocation(mDownsampleProgram, "aPosition")
            mDUvLoc = GLES31.glGetAttribLocation(mDownsampleProgram, "aUV")
            mDTextureLoc = GLES31.glGetUniformLocation(mDownsampleProgram, "uTexture")
            mDHalfPixelLoc = GLES31.glGetUniformLocation(mDownsampleProgram, "uHalfPixel")
            mDVertexArray = GLUtils.createVertexArray(mMeshBuffer, mDPosLoc, mDUvLoc)

            mUpsampleProgram = GLUtils.createProgram(VERTEX_SHADER, UPSAMPLE_FRAG_SHADER)
            mUPosLoc = GLES31.glGetAttribLocation(mUpsampleProgram, "aPosition")
            mUUvLoc = GLES31.glGetAttribLocation(mUpsampleProgram, "aUV")
            mUTextureLoc = GLES31.glGetUniformLocation(mUpsampleProgram, "uTexture")
            mUHalfPixelLoc = GLES31.glGetUniformLocation(mUpsampleProgram, "uHalfPixel")
            mUVertexArray = GLUtils.createVertexArray(mMeshBuffer, mUPosLoc, mUUvLoc)

            mDitherFbo = GLFramebuffer(
                noiseBitmap.width, noiseBitmap.height,
                GLUtils.bitmapToRgb16Buffer(noiseBitmap) { (it - 0.5) / 64.0 },
                GLES31.GL_NEAREST, GLES31.GL_REPEAT,
                GLES31.GL_RGB16F, GLES31.GL_RGB, GLES31.GL_HALF_FLOAT
            )

            val bgBuffer = ByteBuffer.allocateDirect(bgBitmap.rowBytes * bgBitmap.height).run {
                order(ByteOrder.nativeOrder())
            }
            bgBitmap.copyPixelsToBuffer(bgBuffer)
            bgBuffer.position(0)
            mBackgroundFbo = GLFramebuffer(
                bgBitmap.width, bgBitmap.height, bgBuffer,
                GLES31.GL_NEAREST, GLES31.GL_REPEAT,
                GLES31.GL_RGBA8, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE
            )

            GLES31.glUseProgram(mDownsampleProgram)
            GLES31.glUniform1i(mDTextureLoc, 0)

            GLES31.glUseProgram(mUpsampleProgram)
            GLES31.glUniform1i(mUTextureLoc, 0)

            GLES31.glUseProgram(mDitherMixProgram)
            GLES31.glUniform1i(mDMCompositionTextureLoc, 0)
            GLES31.glUniform1i(mDMBlurredTextureLoc, 1)
            GLES31.glUniform1i(mDMDitherTextureLoc, 2)

            GLES31.glUseProgram(mMixProgram)
            GLES31.glUniform1i(mMCompositionTextureLoc, 0)
            GLES31.glUniform1i(mMBlurredTextureLoc, 1)
            GLES31.glUniform1i(mMDitherTextureLoc, 2)

            GLES31.glUseProgram(0)
        }

        private fun prepareBuffers(width: Int, height: Int) {
            mCompositionFbo = GLFramebuffer(width, height)

            val sourceFboWidth = (width * kFboScale).toInt()
            val sourceFboHeight = (height * kFboScale).toInt()
            val fbos = mutableListOf<GLFramebuffer>()
            for (i in 0 until (kMaxPasses + 1)) {
                fbos.add(
                    GLFramebuffer(sourceFboWidth shr i, sourceFboHeight shr i, null,
                        GLES31.GL_LINEAR, GLES31.GL_CLAMP_TO_EDGE,
                        // 2-10-10-10 reversed is the only 10-bpc format in GLES 3.1
                        GLES31.GL_RGB10_A2, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_INT_2_10_10_10_REV
                    )
                )
            }

            mPassFbos = fbos
            mWidth = width
            mHeight = height
        }

        private fun convertGaussianRadius(radius: Int): Pair<Int, Float> {
            for (i in 0 until kMaxPasses) {
                val offsetRange = kOffsetRanges[i]
                val offset = (radius * kFboScale / (2.0).pow(i + 1)).toFloat()
                if (offset in offsetRange) {
                    return (i + 1) to offset
                }
            }

            return 1 to (radius * kFboScale / (2.0).pow(1)).toFloat()
        }

        private fun drawMesh(vertexArray: Int) {
            GLES31.glBindVertexArray(vertexArray)
            GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
            GLES31.glBindVertexArray(0)
        }

        // Execute blur passes, rendering to offscreen texture.
        private fun renderPass(read: GLFramebuffer, draw: GLFramebuffer, halfPixelLoc: Int, vertexArray: Int) {
            Timber.i("blur to ${draw.width}x${draw.height}")

            GLES31.glViewport(0, 0, draw.width, draw.height)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, read.texture)
            draw.bind()
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

            // 1/2 pixel offset in texture coordinate (UV) space
            // Note that this is different from NDC!
            GLES31.glUniform2f(halfPixelLoc, (0.5 / draw.width * mOffset).toFloat(), (0.5 / draw.height * mOffset).toFloat())
            drawMesh(vertexArray)
        }

        // Set up render targets, redirecting output to offscreen texture.
        private fun setAsDrawTarget(width: Int, height: Int, radius: Int) {
            if (width > mWidth || height > mHeight) {
                prepareBuffers(width, height)
            }

            if (radius != mRadius) {
                mRadius = radius
                val (passes, offset) = convertGaussianRadius(radius)
                mPasses = passes
                mOffset = offset
            }

            mCompositionFbo.bind()
            GLES31.glViewport(0, 0, width, height)
        }

        private fun prepare() {
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)

            var read = mCompositionFbo
            var draw = mPassFbos[0]
            read.bindAsReadBuffer()
            draw.bindAsDrawBuffer()
            GLES31.glBlitFramebuffer(
                0, 0, read.width, read.height,
                0, 0, draw.width, draw.height,
                GLES31.GL_COLOR_BUFFER_BIT, GLES31.GL_LINEAR
            )

            Timber.i("Prepare - initial dims ${draw.width}x${draw.height}")

            // Downsample
            GLES31.glUseProgram(mDownsampleProgram)
            for (i in 0 until mPasses) {
                read = mPassFbos[i]
                draw = mPassFbos[i + 1]
                renderPass(read, draw, mDHalfPixelLoc, mDVertexArray)
            }

            // Upsample
            GLES31.glUseProgram(mUpsampleProgram)
            for (i in 0 until mPasses) {
                // Upsampling uses buffers in the reverse direction
                read = mPassFbos[mPasses - i]
                draw = mPassFbos[mPasses - i - 1]
                renderPass(read, draw, mUHalfPixelLoc, mUVertexArray)
            }

            mLastDrawTarget = draw
        }

        // Render blur to the bound framebuffer (screen).
        private fun render(layers: Int, currentLayer: Int) {
            // Now let's scale our blur up. It will be interpolated with the larger composited
            // texture for the first frames, to hide downscaling artifacts.
            val opacity = min(1.0f, mRadius / kMaxCrossFadeRadius)

            // Crossfade using mix shader
            if (currentLayer == layers - 1) {
                GLES31.glUseProgram(mDitherMixProgram)
                GLES31.glUniform1f(mDMBlurOpacityLoc, opacity)
            } else {
                GLES31.glUseProgram(mMixProgram)
                GLES31.glUniform1f(mMBlurOpacityLoc, opacity)
            }
            Timber.i("render - layers=$layers current=$currentLayer dither=${currentLayer == layers - 1}")

            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mCompositionFbo.texture)

            GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mLastDrawTarget.texture)

            GLES31.glActiveTexture(GLES31.GL_TEXTURE2)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mDitherFbo.texture)

            drawMesh(mMVertexArray)

            // Clean up to avoid breaking further composition
            GLES31.glUseProgram(0)
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLUtils.checkErrors()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            init()
            GLES31.glClearColor(1.0f, 0f, 0f, 1f)
        }

        override fun onDrawFrame(gl: GL10?) {
            // Render background
            setAsDrawTarget(mWidth, mHeight, 120)
            GLES31.glUseProgram(mPassthroughProgram)
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mBackgroundFbo.texture)
            GLES31.glUniform1i(mPTextureLoc, 0)
            drawMesh(mPVertexArray)
            GLUtils.checkErrors()

            // Blur
            prepare()
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
            GLES31.glViewport(0, 0, mWidth, mHeight)
            render(1, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            setAsDrawTarget(width, height, 120)
        }
    }

    companion object {
        const val size = 2.0f
        const val translation = 1.0f

        // Downsample FBO to improve performance
        private const val kFboScale = 0.2f
        // We allocate FBOs for this many passes to avoid the overhead of dynamic allocation.
        // If you change this, be sure to update kOffsetRanges as well.
        private const val kMaxPasses = 5
        // To avoid downscaling artifacts, we interpolate the blurred fbo with the full composited
        // image, up to this radius.
        private const val kMaxCrossFadeRadius = 40.0f

        private val kOffsetRanges = listOf(
            1.00f.. 2.50f, // pass 1
            1.25f.. 4.25f, // pass 2
            1.50f..11.25f, // pass 3
            1.75f..18.00f, // pass 4
            2.00f..20.00f  // pass 5
            /* limited by kMaxPasses */
        )

        private const val VERTEX_SHADER = """
        #version 310 es
        precision mediump float;

        in vec2 aPosition;
        in highp vec2 aUV;
        out highp vec2 vUV;

        void main() {
            vUV = aUV;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
        """

        private const val PASSTHROUGH_FRAG_SHADER = """
        #version 310 es
        precision mediump float;

        uniform sampler2D uTexture;

        in highp vec2 vUV;
        out vec4 fragColor;

        void main() {
            fragColor = texture(uTexture, vUV);
        }
        """

        private const val DOWNSAMPLE_FRAG_SHADER = """
        #version 310 es
        precision mediump float;

        uniform sampler2D uTexture;
        uniform vec2 uHalfPixel;

        in highp vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec4 sum = texture(uTexture, vUV) * 4.0;
            sum += texture(uTexture, vUV - uHalfPixel.xy);
            sum += texture(uTexture, vUV + uHalfPixel.xy);
            sum += texture(uTexture, vUV + vec2(uHalfPixel.x, -uHalfPixel.y));
            sum += texture(uTexture, vUV - vec2(uHalfPixel.x, -uHalfPixel.y));
            fragColor = sum * 0.125;
        }
        """

        private const val UPSAMPLE_FRAG_SHADER = """
        #version 310 es
        precision mediump float;

        uniform sampler2D uTexture;
        uniform vec2 uHalfPixel;

        in highp vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec4 sum = texture(uTexture, vUV + vec2(-uHalfPixel.x * 2.0, 0.0));
            sum += texture(uTexture, vUV + vec2(-uHalfPixel.x, uHalfPixel.y)) * 2.0;
            sum += texture(uTexture, vUV + vec2(0.0, uHalfPixel.y * 2.0));
            sum += texture(uTexture, vUV + vec2(uHalfPixel.x, uHalfPixel.y)) * 2.0;
            sum += texture(uTexture, vUV + vec2(uHalfPixel.x * 2.0, 0.0));
            sum += texture(uTexture, vUV + vec2(uHalfPixel.x, -uHalfPixel.y)) * 2.0;
            sum += texture(uTexture, vUV + vec2(0.0, -uHalfPixel.y * 2.0));
            sum += texture(uTexture, vUV + vec2(-uHalfPixel.x, -uHalfPixel.y)) * 2.0;
            fragColor = sum * 0.08333333333333333;
        }
        """

        private const val MIX_FRAG_SHADER = """
        #version 310 es
        precision mediump float;

        uniform sampler2D uCompositionTexture;
        uniform sampler2D uBlurredTexture;
        uniform sampler2D uDitherTexture;
        uniform float uBlurOpacity;

        in highp vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec4 blurred = texture(uBlurredTexture, vUV);
            vec4 composition = texture(uCompositionTexture, vUV);
            fragColor = mix(composition, blurred, 1.0);
        }
        """

        private const val DITHER_MIX_FRAG_SHADER = """
        #version 310 es
        precision mediump float;

        uniform sampler2D uCompositionTexture;
        uniform sampler2D uBlurredTexture;
        uniform sampler2D uDitherTexture;
        uniform float uBlurOpacity;

        in highp vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec2 targetSize = vec2(textureSize(uCompositionTexture, 0));
            vec4 dither = texture(uDitherTexture, vUV / 64.0 * targetSize);

            vec4 blurred = texture(uBlurredTexture, vUV) + dither;
            vec4 composition = texture(uCompositionTexture, vUV);
            fragColor = mix(composition, blurred, 1.0);
        }
        """
    }
}