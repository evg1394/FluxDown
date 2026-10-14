package com.fluxdown.fluxui.material

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.fluxdown.fluxui.theme.FluxTheme

/** 颗粒瓦片（§6.5）：全进程各明暗只生成一次（180×180×4 ≈ 127 KB），运行时仅平铺，绝不逐帧计算噪声。 */
object FluxGrain {
    private const val N = 180
    private val tiles = arrayOfNulls<ImageBitmap>(2)

    /**
     * 180×180 ARGB_8888：RGB 固定（深=白 / 浅=黑），A = 0.9 × 近似高斯噪声（两个均匀数均值，均值 .5 σ≈.2）。
     * `baseFrequency .85` 已超奈奎斯特，实质是逐像素白噪声。
     */
    @Synchronized
    fun tile(dark: Boolean): ImageBitmap {
        val idx = if (dark) 0 else 1
        tiles[idx]?.let { return it }
        val px = IntArray(N * N)
        val rnd = java.util.Random(0x5EEDL)
        val rgb = if (dark) 0xFFFFFF else 0x000000
        for (i in px.indices) {
            val v = ((rnd.nextFloat() + rnd.nextFloat()) * 0.5f * 0.9f * 255f).toInt().coerceIn(0, 255)
            px[i] = (v shl 24) or rgb
        }
        return Bitmap.createBitmap(px, N, N, Bitmap.Config.ARGB_8888).asImageBitmap().also { tiles[idx] = it }
    }
}

/**
 * 胶片颗粒叠层（§6.5）：不透明度 / 混合取自 `colors.grainAlpha / grainBlend`（深 3.5 % SoftLight 白噪点，浅 3 % Multiply 黑噪点；
 * 浅色再高会让纸面显脏）。
 * 在本节点范围内、**子内容之后**铺满绘制；用法：对一个空 `Box(Modifier.fillMaxSize().fluxGrain())` 作叠层，
 * 置于画布之上、内容之下（见 [FluxCanvas]）。瓦片一个纹素 = 1dp（对齐 CSS `background-size:180px`）。
 */
@Composable
fun Modifier.fluxGrain(): Modifier {
    val c = FluxTheme.colors
    val dark = c.dark
    val alpha = c.grainAlpha
    val blend = c.grainBlend
    return this.drawWithCache {
        val shader = ImageShader(FluxGrain.tile(dark), TileMode.Repeated, TileMode.Repeated)
        shader.setLocalMatrix(Matrix().apply { setScale(density, density) })
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush, alpha = alpha, blendMode = blend) }
    }
}
