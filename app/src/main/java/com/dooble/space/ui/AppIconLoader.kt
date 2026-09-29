package com.dooble.space.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用图标加载。
 *
 * ## 为什么不用 Coil / Glide
 *
 * 本工程对 APK 体积有硬约束（lite 变体 12.4MB），而图标加载只需要
 * "从 APK 里抠一个 Drawable 出来 + 一个几十项的缓存"，用不上图片库的
 * 网络、变换、生命周期全套能力。自己写这 100 行比引一个库便宜得多。
 *
 * ## 为什么在**宿主进程**里加载，而不是走容器
 *
 * 容器的 `IPackageManagerProxy` 里有个 `DisableIconLoading` 钩子，
 * 把 `getDrawable` 直接打回 `null`（Bcore 上游为了规避资源错乱做的）。
 * 那条路本来也只作用于**虚拟进程内部**的应用，宿主 UI 根本走不到。
 *
 * 而宿主 UI 手里有 [com.dooble.engine.VirtualApp.apkPath] —— 容器把
 * 空间内应用的 APK 落在自己的虚拟目录里，这个文件宿主进程读得到。
 * 于是直接 `getPackageArchiveInfo` + 自己取资源，链路最短、无副作用。
 *
 * ## 关于 AdaptiveIconDrawable
 *
 * Android 8 之后的图标很多是自适应图标（前景 + 背景两层）。它的
 * `intrinsicWidth/Height` 常常是 -1，所以尺寸必须有兜底，不能拿 0 去
 * `Bitmap.createBitmap` —— 那会直接抛 IllegalArgumentException。
 */
object AppIconLoader {

    /** 缓存条目数。列表一屏十几个，96 足够覆盖来回滚动而不至于吃内存 */
    private const val MAX_ENTRIES = 96

    /** 解码后的最大边长（px）。图标在 UI 里最大只画到 56dp，144px 够 2x 屏 */
    private const val MAX_EDGE_PX = 144

    private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {}

    /** 缓存命中就直接返回，供 Composable 首帧同步取用，避免"先空一下再闪出来" */
    fun peek(key: String): ImageBitmap? = cache.get(key)

    /**
     * 按优先级解析一个空间内应用的图标：
     *  1. 容器虚拟目录里的 APK（最准，且与空间内实际装的那一份一致）
     *  2. 宿主已安装的同名包（虚拟目录被清掉时的兜底）
     *
     * 都拿不到返回 null，UI 会退回首字母方块。
     */
    suspend fun forVirtualApp(
        context: Context,
        packageName: String,
        apkPath: String,
    ): ImageBitmap? {
        val apk = apkPath.takeIf { it.isNotBlank() }?.let(::File)
        if (apk != null && apk.isFile && apk.length() > 0L) {
            fromApk(context, apk.absolutePath)?.let { return it }
        }
        return fromHostPackage(context, packageName)
    }

    /** 从宿主已安装的包取图标 */
    suspend fun fromHostPackage(context: Context, packageName: String): ImageBitmap? {
        val key = "pkg:$packageName"
        cache.get(key)?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                decode(iconDrawable(pm, info), context)
            }.getOrNull()
        }
        return loaded?.also { cache.put(key, it) }
    }

    /** 从一个还没安装的 APK 文件取图标 */
    suspend fun fromApk(context: Context, apkPath: String): ImageBitmap? {
        val key = "apk:$apkPath"
        cache.get(key)?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(apkPath, 0)?.applicationInfo ?: return@runCatching null
                // 这两个字段必须自己补上：getPackageArchiveInfo 只解析清单，
                // 没有它们 Resources 找不到资源表，取图标必然失败
                info.sourceDir = apkPath
                info.publicSourceDir = apkPath
                decode(iconDrawable(pm, info), context)
            }.getOrNull()
        }
        return loaded?.also { cache.put(key, it) }
    }

    /**
     * 自己从 APK 的资源表里取图标。
     *
     * 刻意**不用** `ApplicationInfo.loadIcon(pm)`：那条路内部会先
     * `pm.getApplicationInfo(包名)` 去宿主里查一次，空间内的应用在宿主上
     * 通常并不存在，查不到就直接退化成一个通用默认图标 —— 表现就是
     * "图标全是同一个灰色机器人"。自己走 `getResourcesForApplication`
     * 只依赖我们填好的 sourceDir，结果确定。
     */
    private fun iconDrawable(pm: PackageManager, info: ApplicationInfo): Drawable? {
        val resid = info.icon
        if (resid == 0) return null
        val res = runCatching { pm.getResourcesForApplication(info) }.getOrNull() ?: return null
        return runCatching { res.getDrawable(resid, null) }.getOrNull()
    }

    /**
     * 首字母方块：拿不到真图标时的**降级产物**。
     *
     * ## 为什么不能退回"宿主自己的启动图标"
     *
     * 曾经的做法是 `IconCompat.createWithResource(context, R.mipmap.ic_launcher)` ——
     * 桌面上于是出现一个**看起来就是 Dooble** 的图标。用户完全分不清
     * "这个快捷方式是哪个应用"，会直接认为"图标全都被双开软件替换了"。
     * 那不是一个可接受的降级：它把一个"读不出图标"的问题伪装成了
     * "这个应用就是 Dooble"。
     *
     * 首字母方块至少是诚实的：它表达"这是 X 应用的入口，但图标没读到"，
     * 而且不同应用之间可区分。配色按标签哈希取，同一个应用每次结果一致。
     *
     * @param sizePx 输出位图边长。快捷方式图标建议 192（xxhdpi 的 64dp）
     */
    fun letterTile(label: String, sizePx: Int = 192): ImageBitmap {
        val side = sizePx.coerceIn(48, 512)
        val letter = label.trim().take(1).uppercase().ifBlank { "?" }

        // 取色按标签哈希，保证同名应用每次同色（否则每次钉出来的快捷方式颜色都变）
        val palette = intArrayOf(
            0xFF2B4CFF.toInt(), // Electric
            0xFFFF4D9D.toInt(), // HotPink
            0xFFFF7A3D.toInt(), // Tangerine
            0xFF9BF6C1.toInt(), // Mint
            0xFFB48CFF.toInt(), // Violet
            0xFF6FD3FF.toInt(), // Sky
            0xFFE8FF3A.toInt(), // Acid
        )
        val bg = palette[(label.hashCode() and 0x7FFFFFFF) % palette.size]

        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bg)

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt() // Ink
            textSize = side * 0.58f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val metrics = paint.fontMetrics
        val baseline = side / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(letter, side / 2f, baseline, paint)

        return out.asImageBitmap()
    }

    /** Drawable → ImageBitmap，带尺寸兜底与降采样 */
    private fun decode(drawable: Drawable?, context: Context): ImageBitmap? {        drawable ?: return null

        val fallback = with(context.resources.displayMetrics) { (48f * density).toInt() }
            .coerceAtLeast(1)

        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val w = if (iw > 0) iw else fallback
        val h = if (ih > 0) ih else fallback

        val scale = minOf(1f, MAX_EDGE_PX.toFloat() / maxOf(w, h).toFloat())
        val bw = (w * scale).toInt().coerceIn(1, MAX_EDGE_PX)
        val bh = (h * scale).toInt().coerceIn(1, MAX_EDGE_PX)

        // 常见情形：图标本身就是一张正好尺寸的位图，直接复用，不复制像素
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && bw == bmp.width && bh == bmp.height) return bmp.asImageBitmap()
        }

        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, bw, bh)
        drawable.draw(canvas)
        return out.asImageBitmap()
    }
}

/**
 * 空间内应用的图标。
 *
 * 用 [produceState] 而不是在 ViewModel 里预加载整表：图标解码在 IO 线程，
 * 只在条目真正进入视野时才做，列表很长时不会一次性解码几百张。
 */
@Composable
fun rememberVirtualAppIcon(
    packageName: String,
    apkPath: String,
    versionCode: Long,
): ImageBitmap? {
    val context = LocalContext.current
    val key = "apk:$apkPath"
    val state = produceState<ImageBitmap?>(
        initialValue = AppIconLoader.peek(key),
        packageName,
        apkPath,
        versionCode,
    ) {
        value = AppIconLoader.forVirtualApp(context, packageName, apkPath)
    }
    return state.value
}

/** 宿主已安装应用的图标 */
@Composable
fun rememberHostAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val key = "pkg:$packageName"
    val state = produceState<ImageBitmap?>(
        initialValue = AppIconLoader.peek(key),
        packageName,
    ) {
        value = AppIconLoader.fromHostPackage(context, packageName)
    }
    return state.value
}
