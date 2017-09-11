@file:Suppress("unused")

package io.github.yueeng.meituri

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.view.PagerAdapter
import android.support.v4.view.ViewPager
import android.support.v4.widget.SlidingPaneLayout
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.ImageView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.drawable.ProgressBarDrawable
import com.facebook.drawee.generic.GenericDraweeHierarchy
import com.facebook.drawee.view.DraweeView
import com.facebook.imagepipeline.image.ImageInfo
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File
import java.lang.ref.WeakReference
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.buildSequence

/**
 * Common library
 * Created by Rain on 2017/8/22.
 */

val LOG_TAG = R::class.java.`package`.name.split(".").last()

fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

fun logi(vararg msg: Any?) = debug { Log.i(LOG_TAG, msg.joinToString(", ")) }
fun loge(vararg msg: Any?) = debug { Log.e(LOG_TAG, msg.joinToString(", ")) }
fun logw(vararg msg: Any?) = debug { Log.w(LOG_TAG, msg.joinToString(", ")) }
fun logd(vararg msg: Any?) = debug { Log.d(LOG_TAG, msg.joinToString(", ")) }
fun logv(vararg msg: Any?) = debug { Log.v(LOG_TAG, msg.joinToString(", ")) }

inline fun <reified T : Any> Any.cls(): T? {
    return this as? T
}

fun <T : Any> T?.or(other: () -> T?): T? = this ?: other()

val okhttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(JavaNetCookieJar(CookieManager(null, CookiePolicy.ACCEPT_ALL)))
        .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
        .build()

fun String.httpGet() = try {
    val html = okhttp.newCall(Request.Builder().url(this).build()).execute().body()?.string()
    Pair(this, html)
} catch (e: Exception) {
    e.printStackTrace()
    Pair(this, null)
}

fun Pair<String, String?>.jsoup() = try {
    Jsoup.parse(this.second, this.first)
} catch (e: Exception) {
    e.printStackTrace()
    null
}

fun Element.attrs(vararg key: String): String? {
    return key.firstOrNull { hasAttr(it) }?.let { attr(it) }
}

fun Elements.attrs(vararg key: String): String? {
    return key.firstOrNull { hasAttr(it) }?.let { attr(it) }
}

fun Context.asActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> this.baseContext.asActivity()
    else -> null
}

fun ViewGroup.inflate(layout: Int, attach: Boolean = false): View = LayoutInflater.from(this.context).inflate(layout, this, attach)
val ImageView.bitmap: Bitmap? get() = (this.drawable as? BitmapDrawable)?.bitmap

inline fun <reified T : Fragment> AppCompatActivity.setFragment(container: Int, bundle: () -> Bundle) {
    supportFragmentManager.run {
        val fragment = findFragmentById(container) as? T
                ?: T::class.java.newInstance().apply { arguments = bundle() }
        beginTransaction()
                .replace(container, fragment)
                .commit()
    }
}

class ViewBinder<T, V : View>(private var value: T, private val func: (V, T) -> Unit) {
    private val view = mutableListOf<WeakReference<V>>()
    operator fun plus(v: V): ViewBinder<T, V> = synchronized(this) {
        view += WeakReference(v)
        func(v, value)
        return this
    }

    operator fun minus(v: V): ViewBinder<T, V> = synchronized(this) {
        view -= view.filter { it.get() == v || it.get() == null }
        return this
    }

    operator fun times(v: T): ViewBinder<T, V> = synchronized(this) {
        value = v
        view -= view.filter { it.get() == null }
        view.map { it.get()!! }.forEach { func(it, value) }
        return this
    }

    operator fun invoke(): T {
        return value
    }

    fun each(func: (V) -> Unit): ViewBinder<T, V> = synchronized(this) {
        view -= view.filter { it.get() == null }
        view.map { it.get()!! }.forEach { func(it) }
        return this
    }
}

open class DataHolder<out T : Any>(view: View) : RecyclerView.ViewHolder(view) {
    private lateinit var _value: T
    val value: T get() = _value
    protected open fun bind() {}
    protected open fun bind(i: Int) {}
    @Suppress("UNCHECKED_CAST")
    fun set(v: Any, i: Int) {
        _value = v as T
        bind(i)
        bind()
    }
}

abstract class DataAdapter<T : Any, VH : DataHolder<T>> : RecyclerView.Adapter<VH>() {
    private val data = mutableListOf<T>()
    override fun getItemCount(): Int = data.size
    fun add(vararg items: T): DataAdapter<T, VH> {
        val start = data.size
        data.addAll(items)
        notifyItemRangeInserted(start, data.size - start)
        return this
    }

    fun add(items: Iterable<T>): DataAdapter<T, VH> {
        val start = data.size
        data.addAll(items)
        notifyItemRangeInserted(start, data.size - start)
        return this
    }

    fun clear(): DataAdapter<T, VH> {
        val size = data.size
        data.clear()
        notifyItemRangeRemoved(0, size)
        return this
    }

    fun get(position: Int) = data[position]

    override fun onBindViewHolder(holder: VH?, position: Int) {
        holder?.set(get(position), position)
    }
}

abstract class DataPagerAdapter<T> : PagerAdapter() {
    val data = mutableListOf<T>()
    override fun isViewFromObject(view: View?, `object`: Any?): Boolean = view == `object`

    override fun getCount(): Int = data.size
    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any?) {
        container.removeView(`object` as? View)
    }

    override fun setPrimaryItem(container: ViewGroup?, position: Int, `object`: Any?) {
        super.setPrimaryItem(container, position, `object`)
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val item = data[position]
        val view = container.inflate(R.layout.preview_item)
        view.tag = item
        bind(view, item, position)
        container.addView(view)
        return view
    }

    abstract fun bind(view: View, item: T, position: Int)

    fun getView(pager: ViewPager, position: Int = -1): View? =
            pager.findViewWithTag(data[if (position == -1) pager.currentItem else position])
}

val random = Random(System.currentTimeMillis())

fun randomColor(alpha: Int = 0xFF) = android.graphics.Color.HSVToColor(alpha, arrayOf(random.nextInt(360).toFloat(), 1F, 0.5F).toFloatArray())

class Once {
    private var init = false
    fun run(call: () -> Unit) {
        synchronized(init) {
            if (init) return
            init = true
            call()
        }
    }
}

fun RecyclerView.loadMore(last: Int = 1, call: () -> Unit) {
    this.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        fun load(recycler: RecyclerView) {
            val layout = recycler.layoutManager
            when (layout) {
                is StaggeredGridLayoutManager ->
                    if (layout.findLastVisibleItemPositions(null).max() ?: 0 >= adapter.itemCount - last) call()
                is GridLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= adapter.itemCount - last) call()
                is LinearLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= adapter.itemCount - last) call()
            }
        }

        val once = Once()
        override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
            once.run { load(recycler) }
        }

        override fun onScrollStateChanged(recycler: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return
            load(recycler)
        }
    })
}

class RoundedBackgroundColorSpan(private val backgroundColor: Int) : ReplacementSpan() {
    private var linePadding = 2f // play around with these as needed
    private var sidePadding = 5f // play around with these as needed
    private fun MeasureText(paint: Paint, text: CharSequence, start: Int, end: Int): Float {
        return paint.measureText(text, start, end)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, p4: Paint.FontMetricsInt?): Int {
        return Math.round(MeasureText(paint, text, start, end) + (2 * sidePadding))
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        System.out.println("$start, $end, $x, $top, $y, $bottom, ${paint.fontMetrics.top}, ${paint.fontMetrics.bottom}, ${paint.fontMetrics.leading}, ${paint.fontMetrics.ascent}, ${paint.fontMetrics.descent}, ${paint.fontMetrics.descent - paint.fontMetrics.ascent}")
        val rect = RectF(x, y + paint.fontMetrics.top - linePadding,
                x + getSize(paint, text, start, end, paint.fontMetricsInt),
                y + paint.fontMetrics.bottom + linePadding)
        paint.color = backgroundColor
        canvas.drawRoundRect(rect, 5F, 5F, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, start, end, x + sidePadding, y * 1F, paint)
    }

}

class TagClickableSpan<T>(val tag: T, val call: ((T) -> Unit)? = null) : ClickableSpan() {
    override fun onClick(widget: View) {
        call?.invoke(tag)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = 0xFFFFFFFF.toInt()
        ds.isUnderlineText = false
    }
}

fun <T> List<T>.spannable(separator: CharSequence = " ", string: (T) -> String = { "$it" }, call: ((T) -> Unit)?): SpannableStringBuilder {

    val tags = this.joinToString(separator) { string(it) }
    val span = SpannableStringBuilder(tags)
    fold(0) { i, it ->
        val p = tags.indexOf(string(it), i)
        val e = p + string(it).length
        if (call != null) span.setSpan(TagClickableSpan(it, call), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RoundedBackgroundColorSpan(randomColor(0xBF)), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e
    }
    return span
}

class AccentClickableSpan<in T>(private val t: T, private val call: ((T) -> Unit)?) : ClickableSpan() {
    override fun onClick(p0: View?) {
        call?.invoke(t)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = accentColor
        ds.isUnderlineText = false
    }
}

val accentColor get() = ContextCompat.getColor(MainApplication.current(), R.color.colorAccent)

fun String.numbers() = "\\d+".toRegex().findAll(this).map { it.value }.toList()

fun String.spannable2(tag: List<String>): SpannableStringBuilder = SpannableStringBuilder(this).apply {
    tag.forEach {
        indexAllOf(it).forEach { i ->
            setSpan(ForegroundColorSpan(accentColor), i, i + it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

fun <T> String.spannable(tag: List<T>?, string: ((T) -> String) = { "$it" }, call: ((T) -> Unit)? = null): SpannableStringBuilder = SpannableStringBuilder(this).apply {
    tag?.forEach {
        indexAllOf(string(it)).forEach { i ->
            setSpan(AccentClickableSpan(it, call), i, i + string(it).length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
fun String.indexAllOf(string: String): Sequence<Int> = buildSequence {
    var i = 0
    while (i >= 0) {
        val p = this@indexAllOf.indexOf(string, i)
        if (p == -1) break
        yield(p)
        i = p + string.length
    }
}

fun String.filePath(): String = """\/:*?"<>|""".fold(this) { r, i ->
    r.replace(i, ' ')
}

fun <C : Context> C.save(name: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "${getString(R.string.app_name).filePath()}/$name")

fun String.right(c: Char, ignoreCase: Boolean = false) = this.substring(this.lastIndexOf(c, ignoreCase = ignoreCase).takeIf { it != -1 } ?: 0)
fun String.left(c: Char, ignoreCase: Boolean = false) = this.substring(0, this.indexOf(c, ignoreCase = ignoreCase).takeIf { it != -1 } ?: this.length - 1)

fun <C : Context> C.download(url: String, name: String) {
    val file = save(name)
    if (file.exists()) file.delete()
    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(url))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
//            .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, path)
    dm.enqueue(request)
}

fun <C : Context> C.delay(millis: Long, run: () -> Unit) {
    Handler(mainLooper).postDelayed({ run() }, millis)
}

fun <C : Fragment> C.delay(millis: Long, run: () -> Unit) {
    Handler(context.mainLooper).postDelayed({ run() }, millis)
}

fun <DV : DraweeView<GenericDraweeHierarchy>> DV.progress() = this.apply {
    hierarchy.setProgressBarImage(ProgressBarDrawable())
}

fun <DV : DraweeView<GenericDraweeHierarchy>> DV.load(uri: String) = this.apply {
    val weak = WeakReference(this)
    controller = Fresco.getDraweeControllerBuilderSupplier().get()
            .setUri(Uri.parse(uri))
            .setCallerContext(null)
            .setTapToRetryEnabled(true)
            .setControllerListener(object : BaseControllerListener<ImageInfo>() {
                override fun onFinalImageSet(id: String, imageInfo: ImageInfo?, animatable: Animatable?) {
                    imageInfo?.let {
                        weak.get()?.aspectRatio = 1F * it.width / it.height
                    }

                }
            })
            .setOldController(controller)
            .build()
}

class PagerSlidingPaneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : SlidingPaneLayout(context, attrs, defStyle) {
    private var mInitialMotionX: Float = 0F
    private var mInitialMotionY: Float = 0F
    private val mEdgeSlop: Float = ViewConfiguration.get(context).scaledEdgeSlop.toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        return !isSwipeEnabled || super.onTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isSwipeEnabled) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                mInitialMotionX = ev.x
                mInitialMotionY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val y = ev.y
                if (mInitialMotionX > mEdgeSlop && !isOpen && canScroll(this, false,
                        Math.round(x - mInitialMotionX), Math.round(x), Math.round(y))) {
                    return super.onInterceptTouchEvent(MotionEvent.obtain(ev).apply {
                        action = MotionEvent.ACTION_CANCEL
                    })
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    var isSwipeEnabled: Boolean = true
}

@SuppressLint("Registered")
open class BaseSlideCloseActivity : AppCompatActivity(), SlidingPaneLayout.PanelSlideListener {

    override fun onCreate(state: Bundle?) {
        swipe()
        super.onCreate(state)
    }

    private fun swipe() {
        val swipe = PagerSlidingPaneLayout(this)
        // 通过反射改变mOverhangSize的值为0，
        // 这个mOverhangSize值为菜单到右边屏幕的最短距离，
        // 默认是32dp，现在给它改成0
        try {
            val overhang = SlidingPaneLayout::class.java.getDeclaredField("mOverhangSize")
            overhang.isAccessible = true
            overhang.set(swipe, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        swipe.setPanelSlideListener(this)
        swipe.sliderFadeColor = ContextCompat.getColor(this, android.R.color.transparent)

        // 左侧的透明视图
        val leftView = View(this)
        leftView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        swipe.addView(leftView, 0)

        val decorView = window.decorView as ViewGroup


        // 右侧的内容视图
        val decorChild = decorView.getChildAt(0) as ViewGroup
        decorChild.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
        decorView.removeView(decorChild)
        decorView.addView(swipe)

        // 为 SlidingPaneLayout 添加内容视图
        swipe.addView(decorChild, 1)
    }

    override fun onPanelSlide(panel: View, slideOffset: Float) {

    }

    override fun onPanelOpened(panel: View) {
        finish()
    }

    override fun onPanelClosed(panel: View) {

    }

    fun lockSwipe(lock: Boolean) {
        window.decorView.cls<ViewGroup>()
                ?.getChildAt(0)
                ?.cls<PagerSlidingPaneLayout>()
                ?.isSwipeEnabled = !lock
    }
}