package com.fluxdown.fluxui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成的图标在首次使用时才构建（by lazy）：任何一条坏路径都会在组合中途抛异常、
 * 或因节点为空而不可见。逐个构建并检查每条路径都有节点。
 */
class FluxIconsTest {
    private fun icons(): Map<String, ImageVector> =
        FluxIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .associate { it.name.removePrefix("get") to it.invoke(FluxIcons) as ImageVector }

    @Test
    fun everyIconBuildsWithNonEmptyPaths() {
        val all = icons()
        assertTrue("expected the generated icon set, found ${all.size}", all.size > 100)
        for ((name, icon) in all) {
            val paths = icon.root.filterIsInstance<VectorPath>() +
                icon.root.filterIsInstance<VectorGroup>().flatMap { g -> g.filterIsInstance<VectorPath>() }
            assertTrue("$name has no paths", paths.isNotEmpty())
            paths.forEach { assertTrue("$name has an empty path", it.pathData.isNotEmpty()) }
        }
    }
}
