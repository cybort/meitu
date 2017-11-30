package io.github.yueeng.meitu

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.view.ViewPager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import io.reactivex.rxkotlin.toObservable
import org.jetbrains.anko.bundleOf
import org.jetbrains.anko.downloadManager
import org.jetbrains.anko.toast


/**
 * Preview activity
 * Created by Rain on 2017/8/23.
 */
class PreviewActivity : BaseSlideCloseActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_preview)
        setSupportActionBar(findViewById(R.id.toolbar))
        setFragment<PreviewFragment>(R.id.container) { intent.extras }
    }

    override fun onBackPressed() {
        val preview = supportFragmentManager.findFragmentById(R.id.container) as PreviewFragment
        if (preview.onBackPressed()) return
        super.onBackPressed()
    }
}

@SuppressLint("SetTextI18n")
class PreviewFragment : Fragment() {
    private val album by lazy { arguments?.getParcelable<Album>("album")!! }
    private val name by lazy { album.name }
    private val url by lazy { album.url!! }
    private val count by lazy { album.count }
    private val mtseq by lazy { mtCollectSequence(url) }
    private val adapter by lazy { PreviewAdapter(name) }
    private val busy = ViewBinder<Boolean, View>(false) { v, vt -> v.visibility = if (vt) View.VISIBLE else View.INVISIBLE }
    private val page by lazy { ViewBinder<Int, TextView>(-1) { v, vt -> v.text = "${vt + 1}/$count" } }
    private val current by lazy { ViewBinder(-1, ViewPager::setCurrentItem) }
    private val sliding get() = view?.findViewById<View>(R.id.sliding)?.let { BottomSheetBehavior.from(it) }
    private val thumb by lazy { ThumbAdapter(name) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? =
            inflater.inflate(R.layout.fragment_preview, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        busy + view.findViewById(R.id.busy)
        val pager = view.findViewById<ViewPager>(R.id.pager)
        page + view.findViewById(R.id.text1)
        pager.adapter = adapter
        current + pager
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (position >= adapter.data.size - 3) query()
                page * position
                current * position
            }
        })
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        recycler.adapter = thumb
        recycler.supportsChangeAnimations = false
        recycler.loadMore(2) { query() }

        sliding?.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(sheet: View, offset: Float) {
            }

            var last: Int = BottomSheetBehavior.STATE_COLLAPSED
            override fun onStateChanged(sheet: View, state: Int) {
                if (last == BottomSheetBehavior.STATE_COLLAPSED && state == BottomSheetBehavior.STATE_EXPANDED) {
                    recycler.smoothScrollToPosition(pager.currentItem)
                }
                when (state) {
                    BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.STATE_COLLAPSED -> last = state
                }
            }
        })

        view.findViewById<FloatingActionButton>(R.id.button1).setOnClickListener {
            activity?.permissionWriteExternalStorage {
                adapter.data[current()].let { url ->
                    fun save(override: Boolean) {
                        Save.download(url, name, override) {
                            when (it) {
                                0 -> context?.toast("添加下载队列完成，从通知栏查看下载进度。")
                                DownloadManager.STATUS_SUCCESSFUL -> context?.toast("已经下载过了")
                                else -> context?.toast("已经在下载队列中")
                            }
                        }
                    }
                    if (Save.check(url) != DownloadManager.STATUS_SUCCESSFUL) save(false) else {
                        context?.alert()?.apply {
                            setMessage("注意")
                            setMessage("似乎下载过该图片")
                            setPositiveButton("仍然下载") { _, _ ->
                                save(true)
                            }
                            setNegativeButton("取消", null)
                            create().show()
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.button2).setOnClickListener {
            context?.showInfo(name, url, info) { info = it }
        }
        view.findViewById<View>(R.id.button3).setOnClickListener {
            context?.wrapper()?.popupMenu(it)?.apply {
                setForceShowIcon(true)
                inflate(R.menu.preivew_more)
                menu.findItem(R.id.menu_favorite).isChecked = dbFav.exists(album.url!!)
                setOnMenuItemClickListener {
                    consumer {
                        when (it.itemId) {
                            R.id.menu_download_all -> if (busy()) context?.toast("正在请求数据，请稍后重试。")
                            else activity?.permissionWriteExternalStorage {
                                query(true) { context?.downloadAll(name, adapter.data) }
                            }
                            R.id.menu_favorite -> if (dbFav.exists(album.url!!)) dbFav.del(album.url!!) else AlbumEx.from(album.url!!, album) { dbFav.put(it ?: album) }
                            R.id.menu_thumb -> sliding?.open()
                        }
                    }
                }
                show()
            }
        }
        context?.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        RxBus.instance.subscribe<Int>(this, "tap_preview") {
            if (sliding?.isOpen == true)
                sliding?.close()
            else
                current * (current() + 1)
        }
        RxBus.instance.subscribe<Int>(this, "tap_thumb") {
            current * it
            sliding?.close()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        context?.unregisterReceiver(receiver)
        view?.findViewById<RecyclerView>(R.id.recycler)?.adapter = null
        pager?.adapter = null
        RxBus.instance.unsubscribe(this)
    }

    fun onBackPressed(): Boolean = sliding?.state?.takeIf { it == BottomSheetBehavior.STATE_EXPANDED }?.let {
        sliding?.close()?.let { true }
    } ?: false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            context.downloadManager.query(DownloadManager.Query().apply {
                setFilterById(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
            }).takeIf { it.moveToFirst() }?.let { c ->
                c.getInt(DownloadManager.COLUMN_STATUS)
                        .takeIf { it == DownloadManager.STATUS_SUCCESSFUL }?.let {
                    view?.findViewById<ViewPager>(R.id.pager)
                            ?.findViewWithTag<View>(c.getString(DownloadManager.COLUMN_URI))
                            ?.findViewById<ImageView>(R.id.image2)
                            ?.fadeIn()
                }
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        retainInstance = true
        state?.let {
            page * state.getInt("page")
            mtseq(state.getBundle("uri"))
            adapter.data.addAll(state.getStringArrayList("data"))
            thumb.add(state.getStringArrayList("thumb"))
            info = state.getParcelableArrayList<Bundle>("info")?.map {
                Pair<String, List<Name>>(it.getString("key"), it.getParcelableArrayList("value"))
            }
        } ?: arguments?.let {
            it.getStringArrayList("data")?.let {
                adapter.data.addAll(it)
                thumb.add(it)
            }
            mtseq(it.getBundle("uri"))
            it.getInt("index").let {
                page * it
                current * it
            }
            query()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt("page", page())
        state.putBundle("uri", mtseq())
        state.putStringArrayList("data", ArrayList(adapter.data))
        state.putStringArrayList("thumb", ArrayList(thumb.data))

        state.putParcelableArrayList("info", info?.map {
            bundleOf("key" to it.first).apply {
                putParcelableArrayList("value", ArrayList(it.second))
            }
        }?.let { ArrayList(it) })
    }

    private var info: List<Pair<String, List<Name>>>? = null

    private fun query(all: Boolean = false, call: (() -> Unit)? = null) {
        if (busy() || mtseq.empty()) {
            if (mtseq.empty()) call?.invoke()
            return
        }
        busy * true
        val data = mutableListOf<String>()
        mtseq.toObservable().let {
            if (all) it else it.take(10)
        }.io2main().subscribe({
            data.add(it)
            thumb.add(it)
            adapter.data.add(it)
            adapter.notifyDataSetChanged()
        }, {}, {
            busy * false
            RxBus.instance.post("update_collect", mtseq() to data)
            page * current()
            call?.invoke()
        })
    }

    private val pager get() = view?.findViewById<ViewPager>(R.id.pager)

    class PreviewAdapter(val name: String) : DataPagerAdapter<String>(R.layout.preview_item) {
        override fun bind(view: View, item: String, position: Int) {
            val image2: ImageView = view.findViewById(R.id.image2)
            image2.visibility = if (Save.file(item, name).exists()) View.VISIBLE else View.INVISIBLE
            val image: SubsamplingScaleImageView = view.findViewById(R.id.image)
            val progress: ProgressBar = view.findViewById(R.id.progress)
            image.setOnClickListener {
                RxBus.instance.post("tap_preview", 1)
            }
            GlideApp.with(image)
                    .asFile()
                    .load(item)
                    .progress(item, progress)
                    .into(image)
        }
    }

    class ThumbHolder(view: View, val name: String) : DataHolder<String>(view) {
        private val text: TextView = view.findViewById(R.id.text1)
        private val image: ImageView = view.findViewById(R.id.image)
        private val image2: ImageView = view.findViewById(R.id.image2)
        private val progress: ProgressBar = view.findViewById(R.id.progress)
        override fun bind(i: Int) {
            GlideApp.with(image).load(value).crossFade()
                    .progress(value, progress)
                    .into(image)
            text.text = "${i + 1}"
            image2.visibility = if (Save.file(value, name).exists()) View.VISIBLE else View.INVISIBLE
        }

        init {
            view.setOnClickListener {
                RxBus.instance.post("tap_thumb", adapterPosition)
            }
        }
    }

    class ThumbAdapter(val name: String) : AnimDataAdapter<String, ThumbHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbHolder =
                ThumbHolder(parent.inflate(R.layout.preview_thumb_item), name)

    }
}