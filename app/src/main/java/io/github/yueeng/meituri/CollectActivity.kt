package io.github.yueeng.meituri

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.transition.TransitionManager
import android.support.v4.app.ActivityOptionsCompat
import android.support.v4.app.Fragment
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.facebook.drawee.view.SimpleDraweeView
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread

class CollectActivity : BaseSlideCloseActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_container)
        setFragment<CollectFragment>(R.id.container) { intent.extras }
    }

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        (supportFragmentManager.findFragmentById(R.id.container) as? CollectFragment)?.onActivityReenter(resultCode, data)
        super.onActivityReenter(resultCode, data)
    }
}

class CollectFragment : Fragment() {
    private val album by lazy { arguments?.getParcelable<Album>("album")!! }
    private val name by lazy { album.name }
    private val url by lazy { album.url!! }
    private var uri: String? = null
    private val adapter by lazy { ImageAdapter() }
    private val busy = ViewBinder(false, SwipeRefreshLayout::setRefreshing)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? =
            inflater.inflate(R.layout.fragment_collect, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        setSupportActionBar(view.findViewById(R.id.toolbar))
        title = name
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = StaggeredGridLayoutManager(Settings.PREVIEW_LIST_COLUMN, StaggeredGridLayoutManager.VERTICAL)
        recycler.adapter = adapter
        recycler.loadMore { query() }
        busy + view.findViewById<SwipeRefreshLayout>(R.id.swipe).apply {
            setOnRefreshListener {
                adapter.clear()
                uri = url
                query()
            }
        }
        view.findViewById<FloatingActionButton>(R.id.button1).setOnClickListener {
            recycler?.let { view ->
                TransitionManager.beginDelayedTransition(view)
                (view.layoutManager as? StaggeredGridLayoutManager)?.let {
                    it.spanCount = (it.spanCount + 1).takeIf { it <= Settings.MAX_PREVIEW_LIST_COLUMN } ?: 1
                    Settings.PREVIEW_LIST_COLUMN = it.spanCount
                }
            }
        }
        RxBus.instance.subscribe<Int>(this, "hack_fresco") {
            recycler?.adapter?.notifyDataSetChanged()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        uri = url
        retainInstance = true
        setHasOptionsMenu(true)
        state?.let {
            uri = state.getString("uri")
            adapter.add(state.getStringArrayList("data"))
        } ?: { query() }()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("uri", uri)
        state.putStringArrayList("data", ArrayList(adapter.data))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        view?.findViewById<RecyclerView>(R.id.recycler)?.adapter = null
        RxBus.instance.unsubscribe(this)
    }

    private fun query() {
        if (busy() || uri == null) {
            return
        }
        busy * true
        doAsync {
            val dom = uri!!.httpGet().jsoup()
            val list = dom?.select(".content img.tupian_img")?.map { it.attr("abs:src") }
            val next = dom?.select("#pages span+a")?.let {
                !it.`is`(".a1") to it.attr("abs:href")
            }
            uiThread {
                busy * false
                uri = if (next?.first == true) next.second else null
                if (list != null) {
                    adapter.add(list)
                }
            }
        }
    }

    private val recycler get() = view?.findViewById<RecyclerView>(R.id.recycler)
    fun onActivityReenter(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val eindex = data.getIntExtra("exit_index", -1)
            val edata = data.getStringArrayListExtra("exit_data")
            uri = data.getStringExtra("exit_uri")
            adapter.add(edata.drop(adapter.data.size))
            activity?.exitSharedElementCallback {
                recycler?.findViewHolderForAdapterPosition2<ImageHolder>(eindex)?.let {
                    it.image to it.value
                } ?: throw IllegalArgumentException()
            }
            recycler?.let { recycler ->
                activity?.supportPostponeEnterTransition()
                recycler.scrollToPosition(eindex)
                recycler.startPostponedEnterTransition()
            }
        }
    }

    inner class ImageHolder(view: View) : DataHolder<String>(view) {
        private val text: TextView = view.findViewById(R.id.text1)
        val image: SimpleDraweeView = view.findViewById(R.id.image)
        private val image2: ImageView = view.findViewById(R.id.image2)
        @SuppressLint("SetTextI18n")
        override fun bind(i: Int) {
            image.load(value).aspectRatio = 2F / 3F
            text.text = "${i + 1}"
            image2.visibility = if (Save.file(value, name).exists()) View.VISIBLE else View.INVISIBLE
        }

        init {
            view.setOnClickListener {
                activity?.let {
                    it.startActivity(Intent(it, PreviewActivity::class.java)
                            .putExtra("album", album)
                            .putExtra("data", ArrayList(adapter.data))
                            .putExtra("uri", uri)
                            .putExtra("index", adapterPosition),
                            ActivityOptionsCompat.makeSceneTransitionAnimation(it,
                                    image to4 value).toBundle())
                }
            }
            image.progress()
        }
    }

    inner class ImageAdapter : DataAdapter<String, ImageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
                ImageHolder(parent.inflate(R.layout.list_collect_item))

    }
}