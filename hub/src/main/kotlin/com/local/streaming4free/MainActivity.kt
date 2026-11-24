package com.local.streaming4free

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose

/**
 * IPTV4FREE: the Kodi interface's look (rail with the logo, groups, channels, now/next with the live preview) as a
 * native Android TV app holding everything the STREAMING4FREE hub had. Four columns driven by the remote:
 * menu → sub-category → content → details. OK plays or opens, → shows a channel's programme, Back goes one step back,
 * and from full screen back to the menu with the channel still playing in the preview.
 */
class MainActivity : Activity() {
    private enum class Mode { NONE, PREVIEW, FULL }
    private enum class Col { RAIL, GROUPS, ITEMS, PROGS, SEARCH }

    /** What a column shows: the row it was opened from, its rows, and the row the remote is on. */
    private class Level(val from: Item, val items: List<Item>, var pos: Int)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Store.localized(base))

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playback: Playback
    private var mode = Mode.NONE
    private var resumeFull = false // stopped while full screen: plays again on return, as the hub's player did

    private lateinit var browse: ViewGroup
    private lateinit var railList: RecyclerView
    private lateinit var groupList: RecyclerView
    private lateinit var itemList: RecyclerView
    private lateinit var progList: RecyclerView
    private lateinit var groupsTitle: TextView
    private lateinit var searchBox: EditText
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var footer: TextView
    private lateinit var previewLogo: ImageView
    private lateinit var dTitle: TextView
    private lateinit var dSub: TextView
    private lateinit var dEpg: View
    private lateinit var dNowTime: TextView
    private lateinit var dNow: TextView
    private lateinit var dBar: ProgressBar
    private lateinit var dNextTime: TextView
    private lateinit var dNext: TextView
    private lateinit var dBody: TextView
    private lateinit var progDesc: TextView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private lateinit var playingBar: TextView
    private lateinit var banner: View
    private lateinit var splash: View

    private lateinit var railRows: Rows
    private lateinit var groupRows: Rows
    private lateinit var itemRows: Rows
    private lateinit var progRows: Rows

    private var menu: Menu? = null
    private var rail: List<Section> = emptyList()
    private var railPos = -1 // the open section
    private var railFocus = 0
    private var col2: Level? = null
    private var col3: Level? = null
    private var progPos = 0
    private val back = ArrayDeque<Pair<Level, Level>>() // columns 2 and 3 before each step into a sub-category
    private var focusCol = Col.RAIL
    private var zap: List<Channel> = emptyList() // the list a channel was started from: up / down in full screen
    private var favKeys: Set<String> = emptySet()
    private var query = ""
    private var results: List<Section> = emptyList()
    private var listsLoading = false
    private var railTask: Runnable? = null
    private var groupTask: Runnable? = null
    private var searchTask: Runnable? = null
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.main)
        browse = findViewById(R.id.browse)
        railList = findViewById(R.id.rail)
        groupList = findViewById(R.id.groups)
        itemList = findViewById(R.id.items)
        progList = findViewById(R.id.progs)
        groupsTitle = findViewById(R.id.groups_title)
        searchBox = findViewById(R.id.search)
        title = findViewById(R.id.title)
        count = findViewById(R.id.count)
        empty = findViewById(R.id.empty)
        status = findViewById(R.id.status)
        footer = findViewById(R.id.footer)
        previewLogo = findViewById(R.id.preview_logo)
        dTitle = findViewById(R.id.d_title)
        dSub = findViewById(R.id.d_sub)
        dEpg = findViewById(R.id.d_epg)
        dNowTime = findViewById(R.id.d_now_time)
        dNow = findViewById(R.id.d_now)
        dBar = findViewById(R.id.d_bar)
        dNextTime = findViewById(R.id.d_next_time)
        dNext = findViewById(R.id.d_next)
        dBody = findViewById(R.id.d_body)
        progDesc = findViewById(R.id.prog_desc)
        playerBox = findViewById(R.id.player_box)
        playerView = findViewById(R.id.player_view)
        message = findViewById(R.id.message)
        playingBar = findViewById(R.id.playing)
        banner = findViewById(R.id.banner)
        splash = findViewById(R.id.splash)
        playback = Playback(this, playerView, message) { stopPlayback() }
        splash.isFocusable = true
        splash.requestFocus() // else Android gives the first rail row the focus before the menu is ready

        railRows = Rows(R.layout.row_rail, { v, i, _ -> (v as TextView).text = i.title }, ::onRailFocus, { flushRail(); focus(Col.GROUPS) }, {})
        groupRows = Rows(R.layout.row_group, ::bindGroup, ::onGroupFocus, { flushGroup(); focus(Col.ITEMS) }, {})
        itemRows = Rows(R.layout.row_item, ::bindItem, ::onItemFocus, { p -> col3?.items?.getOrNull(p)?.let { activate(it, p) } },
            { p -> col3?.items?.getOrNull(p)?.let(::toggleFavorite) })
        progRows = Rows(R.layout.row_prog, ::bindProg, ::onProgFocus, { p -> (progRows.items.getOrNull(p) as? Programme)?.let { play(it.channel, listOf(it.channel)) } }, {})
        for ((rv, rows) in listOf(railList to railRows, groupList to groupRows, itemList to itemRows, progList to progRows)) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.itemAnimator = null // a redrawn row keeps its view, so it keeps the focus
            rv.setHasFixedSize(true)
            rv.adapter = rows
        }
        searchBox.setOnFocusChangeListener { _, has -> if (has) focusCol = Col.SEARCH }
        searchBox.setOnEditorActionListener { _, _, _ ->
            keyboard(false)
            runSearch(searchBox.text.toString(), true)
            true
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchTask?.let(ui::removeCallbacks)
                searchTask = Runnable { runSearch(s.toString(), false) }.also { ui.postDelayed(it, 600) }
            }
        })
        footer.text = getString(R.string.footer)
        previewLogo.setImageResource(R.drawable.iptv4free_logo)

        val app = applicationContext
        IO.execute {
            val m = try {
                val lib = Library.get(app)
                Guide.load(app)
                Menu(this, lib) // this activity's resources: the language chosen in the settings
            } catch (e: Exception) {
                Log.e(TAG, "library: $e")
                null
            }
            ui.post { if (!isDestroyed) ready(m) }
            if (m != null && Guide.stale(app)) {
                ui.post { status.text = getString(R.string.guide_loading) }
                val ok = Guide.refresh(app)
                ui.post { if (!isDestroyed) guideUpdated(ok) }
            }
        }
    }

    /** Library and menu read: the start screen fades, the remote lands on the last thing watched, else on TV. */
    private fun ready(m: Menu?) {
        if (m == null) {
            findViewById<TextView>(R.id.splash_text).text = getString(R.string.list_offline)
            return
        }
        menu = m
        rail = m.rail
        railRows.set(rail, -1)
        favKeys = Store.favorites().mapTo(HashSet()) { it.key }
        val resume = rail.indexOfFirst { it.key == "resume" }
        if (Store.history().isNotEmpty() && resume >= 0) {
            railFocus = resume
            openRail(resume)
            focus(Col.ITEMS)
        } else {
            openRail(0)
            focus(Col.RAIL)
        }
        splash.animate().alpha(0f).setDuration(400).withEndAction { splash.visibility = View.GONE }.start()
    }

    // ---------- columns ----------

    private fun openRail(p: Int) {
        railTask = null
        val s = rail.getOrNull(p) ?: return
        railPos = p
        railRows.activate(p)
        back.clear()
        hideProgs()
        val search = s.key == "search"
        searchBox.visibility = if (search) View.VISIBLE else View.GONE
        groupsTitle.visibility = if (search) View.INVISIBLE else View.VISIBLE
        if (search) loadListsForSearch()
        val kids = if (search) results else s.load()
        setCol2(Level(s, kids, 0))
        val first = kids.firstOrNull()
        if (first != null) show3(first)
        else setCol3(Level(s, emptyList(), 0), getString(if (!search) R.string.empty else if (query.trim().length < 2) R.string.search_type else R.string.search_none, query.trim()))
    }

    /** Column 3 shows what [i] holds; slow rows (downloads, guide) load off the main thread. */
    private fun show3(i: Item, pos: Int = 0) {
        groupTask = null
        val kids = when (i) {
            is Section -> if (i.slow) null else i.load()
            is Folder -> i.channels
            else -> listOf(i)
        }
        if (kids != null) {
            setCol3(Level(i, kids, pos))
            return
        }
        val s = i as Section
        setCol3(Level(s, emptyList(), 0), getString(R.string.loading))
        IO.execute {
            val r = try { s.load() } catch (e: Exception) { Log.w(TAG, "${s.key}: $e"); null }
            ui.post {
                if (col3?.from !== s || isDestroyed) return@post
                setCol3(Level(s, r.orEmpty(), 0), getString(if (r == null) R.string.list_offline else emptyText(s)))
            }
        }
    }

    private fun emptyText(s: Item) = when {
        s.key.startsWith("fav") -> R.string.hint_favorites
        s.key.startsWith("resume") -> R.string.hint_history
        s.key.startsWith("guide") || s.key == "sport/guide" -> if (Guide.loaded()) R.string.guide_nothing else R.string.guide_loading
        else -> R.string.empty
    }

    private fun setCol2(l: Level) {
        col2 = l
        groupRows.set(l.items, l.pos)
        groupsTitle.text = l.from.title
        (groupList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(l.pos.coerceAtLeast(0), 0)
    }

    private fun setCol3(l: Level, emptyText: String = getString(emptyText(l.from))) {
        col3 = l
        itemRows.set(l.items, -1)
        title.text = l.from.title
        count.text = when {
            l.items.isEmpty() -> ""
            l.items.all { it is Channel } -> getString(R.string.channels_count, l.items.size)
            else -> getString(R.string.items_count, l.items.size)
        }
        empty.show(if (l.items.isEmpty()) emptyText else null)
        (itemList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(l.pos.coerceIn(0, maxOf(0, l.items.size - 1)), itemList.height / 3)
    }

    /** One step into a sub-category: the list it is in becomes column 2, its content column 3. */
    private fun enter(i: Item, p: Int) {
        val c2 = col2 ?: return
        val c3 = col3 ?: return
        back.addLast(c2 to c3)
        c3.pos = p
        setCol2(Level(c3.from, c3.items, p))
        show3(i)
        if (!focus(Col.ITEMS)) focus(Col.GROUPS)
    }

    private fun pop(to: Col) {
        val (c2, c3) = back.removeLast()
        setCol2(c2)
        setCol3(c3)
        if (!focus(to)) focus(Col.GROUPS)
    }

    /** Puts the remote on [col] at its row. False when the column has nothing to focus. */
    private fun focus(col: Col): Boolean {
        // the search box sits above the results: an empty result column means the box
        val target = if (col == Col.GROUPS && groupRows.itemCount == 0 && searchBox.visibility == View.VISIBLE) Col.SEARCH else col
        val ok = when (target) {
            Col.RAIL -> focusRow(railList, railFocus)
            Col.GROUPS -> groupRows.itemCount > 0 && focusRow(groupList, col2?.pos ?: 0)
            Col.ITEMS -> itemRows.itemCount > 0 && focusRow(itemList, col3?.pos ?: 0)
            Col.PROGS -> progRows.itemCount > 0 && focusRow(progList, progPos)
            Col.SEARCH -> searchBox.requestFocus()
        }
        if (ok) focusCol = target
        return ok
    }

    private var focusGen = 0 // a newer focus request cancels an older one still waiting for its row

    private fun focusRow(rv: RecyclerView, pos: Int): Boolean {
        val n = rv.adapter?.itemCount ?: 0
        if (n == 0) return false
        val p = pos.coerceIn(0, n - 1)
        val gen = ++focusGen
        val h = rv.findViewHolderForAdapterPosition(p)
        if (h != null && h.itemView.isAttachedToWindow && !rv.isLayoutRequested) return h.itemView.requestFocus()
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, rv.height / 3)
        var tries = 0
        // the row exists only once the list is laid out (a frame or two after new content)
        val later = object : Runnable {
            override fun run() {
                if (gen != focusGen || isDestroyed) return
                val v = rv.findViewHolderForAdapterPosition(p)?.itemView
                if (v != null && v.isAttachedToWindow && !rv.isLayoutRequested) v.requestFocus() else if (++tries < 20) ui.postDelayed(this, 25)
            }
        }
        ui.post(later)
        return true
    }

    private fun onRailFocus(p: Int) {
        focusCol = Col.RAIL
        railFocus = p
        if (p == railPos && railTask == null) return
        railRows.activate(p)
        railTask?.let(ui::removeCallbacks)
        railTask = Runnable { openRail(p) }.also { ui.postDelayed(it, 150) }
    }

    private fun flushRail() = railTask?.let { ui.removeCallbacks(it); it.run() }

    private fun onGroupFocus(p: Int) {
        focusCol = Col.GROUPS
        val l = col2 ?: return
        l.pos = p
        groupRows.activate(p)
        val item = l.items.getOrNull(p) ?: return
        showDetails(item)
        if (col3?.from === item && groupTask == null) return
        groupTask?.let(ui::removeCallbacks)
        groupTask = Runnable { show3(item) }.also { ui.postDelayed(it, 120) }
    }

    private fun flushGroup() = groupTask?.let { ui.removeCallbacks(it); it.run() }

    private fun onItemFocus(p: Int) {
        focusCol = Col.ITEMS
        val l = col3 ?: return
        l.pos = p
        l.items.getOrNull(p)?.let(::showDetails)
    }

    private fun onProgFocus(p: Int) {
        focusCol = Col.PROGS
        progPos = p
        val g = (progRows.items.getOrNull(p) as? Programme)?.prog ?: return
        progDesc.text = listOf(g.category, g.desc).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The row the remote is on, in the content or programme column. */
    private fun current(): Item? = when (focusCol) {
        Col.ITEMS -> col3?.let { it.items.getOrNull(it.pos) }
        Col.PROGS -> (progRows.items.getOrNull(progPos) as? Programme)?.channel
        else -> null
    }

    // ---------- rows ----------

    private fun bindGroup(v: View, i: Item, p: Int) {
        v.findViewById<TextView>(R.id.label2).text = when (i) {
            is Section -> i.label2
            is Folder -> i.source
            else -> ""
        }
        v.findViewById<TextView>(R.id.label).text = i.title
    }

    private fun bindItem(v: View, i: Item, p: Int) {
        val num = v.findViewById<TextView>(R.id.num)
        val logo = v.findViewById<ImageView>(R.id.logo)
        val sub = v.findViewById<TextView>(R.id.sub)
        val bar = v.findViewById<ProgressBar>(R.id.bar)
        v.findViewById<TextView>(R.id.name).text = i.title
        v.findViewById<TextView>(R.id.badge).text = if (i.key in favKeys) getString(R.string.fav_badge) else ""
        v.findViewById<View>(R.id.marker).visibility = if (i is Channel && mode != Mode.NONE && i.url == playback.channel?.url) View.VISIBLE else View.GONE
        v.alpha = if (i is Channel && Store.isDead(i.url)) 0.45f else 1f
        val now = System.currentTimeMillis()
        when (i) {
            is Channel -> {
                num.text = (p + 1).toString()
                logo.logo(i.logo)
                val cur = Guide.now(i, country(i), now)
                sub.text = cur?.title ?: rowFacts(i)
                bar.show(cur?.progress(now))
            }
            is Programme -> {
                num.text = hm(i.prog.start)
                logo.logo(i.channel.logo)
                sub.text = i.channel.title + " · " + range(i.prog)
                bar.show(if (i.prog.on(now)) i.prog.progress(now) else null)
            }
            is App -> {
                num.text = ""
                logo.appArt(this, i)
                sub.text = appLine(i)
                bar.show(null)
            }
            else -> {
                num.text = ""
                logo.dispose()
                logo.setImageResource(if (i is Setting) R.drawable.ph_setting else R.drawable.ph_folder)
                sub.text = when (i) {
                    is Setting -> i.value
                    is Folder -> getString(R.string.channels_count, i.channels.size)
                    is Section -> if (i.slow) "" else getString(R.string.items_count, i.load().size)
                    else -> ""
                }
                bar.show(null)
            }
        }
    }

    private fun bindProg(v: View, i: Item, p: Int) {
        val g = (i as Programme).prog
        v.findViewById<TextView>(R.id.time).text = range(g)
        v.findViewById<TextView>(R.id.what).text = g.title
    }

    private fun country(ch: Channel) = Library.info(ch.url)?.country ?: channelCountry(ch.id)

    /** A channel's row without a guide: what the lists say about it. */
    private fun rowFacts(ch: Channel): String {
        if (Store.isDead(ch.url)) return getString(R.string.dead)
        if (ch.isYouTube) return getString(R.string.on_youtube)
        val sources = candidates(ch, Catalog.all(this), Playlists.loaded(Playlists.SOURCES.first { it.name == "M3UPT" })).size
        return listOf(if (ch.geo) getString(R.string.hint_geo) else "", if (ch.headers) getString(R.string.hint_headers) else "",
            if (sources > 1) getString(R.string.hint_sources, sources) else "", ch.quality.substringBefore(' '),
            ch.group.substringBefore(';')).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun appLine(a: App) = when {
        a.site != null -> getString(R.string.app_site) + " · " + getString(a.desc)
        a.installedPackage(packageManager) != null -> getString(a.terms.label) + " · " + getString(a.desc)
        else -> getString(R.string.to_install) + " · " + getString(a.terms.label)
    }

    // ---------- details panel ----------

    private fun showDetails(i: Item) {
        if (progList.visibility == View.VISIBLE) return // the programme column keeps the panel
        dTitle.text = i.title
        dEpg.visibility = View.GONE
        when (i) {
            is Channel -> channelDetails(i)
            is Programme -> {
                dSub.text = i.channel.title + " · " + range(i.prog)
                dBody.show(listOf(i.prog.category, i.prog.desc).filter { it.isNotEmpty() }.joinToString("\n"))
            }
            is App -> {
                dSub.text = getString(i.terms.label)
                dBody.show(getString(i.desc) + "\n\n" + getString(when {
                    i.site != null -> R.string.app_site_ok
                    i.installedPackage(packageManager) != null -> R.string.app_ok
                    i.onPlayStore -> R.string.app_install_ok
                    else -> R.string.not_installed
                }))
            }
            is Folder -> { dSub.text = getString(R.string.channels_count, i.channels.size); dBody.show(null) }
            is Section -> { dSub.text = if (i.slow) "" else getString(R.string.items_count, i.load().size); dBody.show(null) }
            is Setting -> { dSub.text = i.value; dBody.show(null) }
        }
        if (mode != Mode.PREVIEW) when (i) {
            is Channel -> previewLogo.logo(i.logo)
            is Programme -> previewLogo.logo(i.channel.logo)
            is App -> previewLogo.appArt(this, i)
            else -> { previewLogo.dispose(); previewLogo.setImageResource(R.drawable.iptv4free_logo) }
        }
    }

    private fun channelDetails(ch: Channel) {
        val info = Library.info(ch.url)
        val now = System.currentTimeMillis()
        val sched = Guide.schedule(ch, country(ch))
        dSub.text = listOfNotNull(info?.let { getString(R.string.channel_n, it.n) }, ch.group.substringBefore(';').ifEmpty { null }).joinToString(" · ")
        val cur = sched.firstOrNull { it.on(now) }
        val nxt = sched.firstOrNull { it.start >= now }
        if (cur != null || nxt != null) {
            dEpg.visibility = View.VISIBLE
            dBody.visibility = View.GONE
            dNowTime.text = cur?.let(::range).orEmpty()
            dNow.text = cur?.title ?: "—"
            dBar.show(cur?.progress(now))
            dNextTime.text = nxt?.let(::range).orEmpty()
            dNext.text = nxt?.title.orEmpty()
            return
        }
        val lines = ArrayList<String>()
        if (Store.isDead(ch.url)) lines += getString(R.string.dead)
        if (ch.isYouTube) lines += getString(R.string.on_youtube)
        if (ch.geo) lines += getString(R.string.hint_geo)
        if (ch.headers) lines += getString(R.string.headers_needed)
        val sources = candidates(ch, Catalog.all(this), Playlists.loaded(Playlists.SOURCES.first { it.name == "M3UPT" })).size
        if (sources > 1) lines += getString(R.string.hint_sources, sources)
        if (ch.quality.isNotEmpty()) lines += ch.quality.substringBefore(' ')
        if (ch.source.isNotEmpty()) lines += getString(R.string.source_line, ch.source)
        officialApp(ch.id, ch.title)?.let { lines += getString(R.string.official_app, it) }
        rightsService(ch.id, ch.title)?.let { lines += getString(R.string.rights_short, it) }
        lines += getString(if (Guide.loaded()) R.string.guide_none else R.string.guide_loading)
        dBody.show(lines.joinToString("\n"))
    }

    private fun openProgs(ch: Channel) {
        val sched = Guide.schedule(ch, country(ch))
        if (sched.isEmpty()) {
            Toast.makeText(this, if (Guide.loaded()) R.string.guide_none else R.string.guide_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val items = sched.filter { it.stop > now - 3600_000L }.map { Programme(ch, it) }
        progPos = items.indexOfFirst { it.prog.on(now) }.coerceAtLeast(0)
        progRows.set(items, progPos)
        dEpg.visibility = View.GONE
        dBody.visibility = View.GONE
        progList.visibility = View.VISIBLE
        progDesc.visibility = View.VISIBLE
        focus(Col.PROGS)
    }

    private fun hideProgs() {
        if (progList.visibility != View.VISIBLE) return
        progList.visibility = View.GONE
        progDesc.visibility = View.GONE
        col3?.let { it.items.getOrNull(it.pos) }?.let(::showDetails)
    }

    // ---------- actions ----------

    private fun activate(i: Item, p: Int) {
        when (i) {
            is Channel -> play(i, col3?.items.orEmpty().filterIsInstance<Channel>())
            is Programme -> play(i.channel, listOf(i.channel))
            is App -> openApp(this, i)
            is Section, is Folder -> enter(i, p)
            is Setting -> setting(i)
        }
    }

    private fun play(ch: Channel, list: List<Channel>) {
        if (ch.isYouTube) {
            openYouTube(this, ch)
            return
        }
        if (playback.channel?.url == ch.url && playback.playing && !playback.failed) { // Kodi: OK on the channel playing = full screen
            setMode(Mode.FULL)
            return
        }
        zap = list.filter { !it.isYouTube }.ifEmpty { listOf(ch) }
        playback.open(ch)
        setMode(Mode.FULL)
        banner(ch)
    }

    private fun zapBy(d: Int) {
        if (zap.size < 2) return
        val cur = zap.indexOfFirst { it.url == playback.channel?.url }.coerceAtLeast(0)
        val next = zap[(cur + d).mod(zap.size)]
        playback.open(next)
        banner(next)
        col3?.let { l -> l.items.indexOfFirst { (it as? Channel)?.url == next.url }.takeIf { it >= 0 }?.let { l.pos = it } } // Back lands on it
    }

    /** Number, logo, name and programme over the picture for a few seconds. */
    private fun banner(ch: Channel) {
        val n = zap.indexOfFirst { it.url == ch.url }
        findViewById<TextView>(R.id.banner_num).text = if (n >= 0) (n + 1).toString() else ""
        findViewById<ImageView>(R.id.banner_logo).logo(ch.logo)
        findViewById<TextView>(R.id.banner_title).text = ch.title
        val now = System.currentTimeMillis()
        val cur = Guide.now(ch, country(ch), now)
        findViewById<TextView>(R.id.banner_now).text = cur?.let { getString(R.string.now_label) + "  " + range(it) + "  " + it.title } ?: ch.group.substringBefore(';')
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 5000)
    }

    private var browseCol = Col.RAIL // where the remote was in the menu before full screen

    private fun setMode(m: Mode) {
        if (mode != Mode.FULL) browseCol = focusCol
        mode = m
        // while the player takes or gives back the screen (its controls hide), Android would hand the focus to the first menu row
        browse.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val d = resources.displayMetrics.density
        when (m) {
            Mode.FULL -> {
                playerBox.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                playerBox.visibility = View.VISIBLE
                playingBar.visibility = View.GONE
                playerView.useController = true
                message(22f, 48, 24)
                browse.visibility = View.INVISIBLE
            }
            Mode.PREVIEW -> {
                playerBox.layoutParams = FrameLayout.LayoutParams((290 * d).toInt(), (163 * d).toInt()).apply {
                    leftMargin = (650 * d).toInt()
                    topMargin = (70 * d).toInt()
                }
                playerBox.visibility = View.VISIBLE
                playerView.hideController()
                playerView.useController = false
                banner.visibility = View.GONE
                playingBar.show(getString(R.string.playing_label, playback.channel?.title.orEmpty()))
                message(11f, 4, 6)
                browse.visibility = View.VISIBLE
            }
            Mode.NONE -> {
                playerBox.visibility = View.GONE
                banner.visibility = View.GONE
                browse.visibility = View.VISIBLE
            }
        }
        footer.text = getString(R.string.footer) + if (m == Mode.PREVIEW) getString(R.string.footer_preview) else ""
        if (m != Mode.FULL) {
            itemRows.redraw() // playing marker
            browse.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            focus(browseCol)
        }
    }

    private fun message(size: Float, margin: Int, padding: Int) {
        val d = resources.displayMetrics.density
        message.textSize = size
        message.setPadding((padding * d).toInt(), (padding * d).toInt(), (padding * d).toInt(), (padding * d).toInt())
        message.layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, android.view.Gravity.CENTER).apply {
            marginStart = (margin * d).toInt()
            marginEnd = (margin * d).toInt()
        }
    }

    /** Back in full screen: the menu, with the channel still playing in the preview; a channel that never played just stops. */
    private fun leaveFull() {
        banner.visibility = View.GONE
        if (playback.started && !playback.failed) setMode(Mode.PREVIEW) else stopPlayback()
    }

    private fun stopPlayback() {
        playback.stop()
        setMode(Mode.NONE)
    }

    private fun toggleFavorite(i: Item) {
        val target = if (i is Programme) i.channel else i
        if (target !is Channel && target !is App) return
        val added = Store.toggleFavorite(target)
        Toast.makeText(this, if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
    }

    private val onStore = {
        favKeys = Store.favorites().mapTo(HashSet()) { it.key }
        val l = col3
        val s = l?.from as? Section
        if (l != null && s != null && !s.slow && (s.key.startsWith("fav/") || s.key.startsWith("resume/"))) {
            setCol3(Level(s, s.load(), l.pos))
            if (focusCol == Col.ITEMS && !focus(Col.ITEMS)) focus(Col.GROUPS)
        } else itemRows.redraw()
    }

    private fun setting(s: Setting) {
        val app = applicationContext
        when {
            s.id.startsWith("lang:") -> if (Store.lang != s.id.substring(5)) {
                Store.lang = s.id.substring(5)
                recreate()
            }
            s.id.startsWith("q:") -> {
                Store.maxHeight = s.id.substring(2).toInt()
                reload3()
            }
            s.id == "refresh" -> {
                Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show()
                IO.execute {
                    val ok = Playlists.SOURCES.count { src -> try { Playlists.folders(app, src, true); true } catch (e: Exception) { Log.w(TAG, "refresh ${src.name}: $e"); false } }
                    ui.post { Toast.makeText(app, app.getString(R.string.refresh_done, ok, Playlists.SOURCES.size), Toast.LENGTH_LONG).show() }
                }
            }
            s.id == "guide" -> {
                status.text = getString(R.string.guide_loading)
                IO.execute {
                    val ok = Guide.refresh(app)
                    ui.post { if (!isDestroyed) guideUpdated(ok) }
                }
            }
            s.id == "clear" -> {
                Store.clearHistory()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun reload3() {
        val l = col3 ?: return
        val s = l.from as? Section ?: return
        if (s.slow) return
        setCol3(Level(s, s.load(), l.pos))
        focus(Col.ITEMS)
    }

    private fun guideUpdated(ok: Int) {
        status.text = getString(R.string.guide_done, ok, Guide.SOURCES.size)
        ui.postDelayed({ status.text = "" }, 8000)
        itemRows.redraw()
        current()?.let(::showDetails)
    }

    // ---------- search ----------

    private fun loadListsForSearch() {
        if (listsLoading) return
        listsLoading = true
        val app = applicationContext
        for (s in Playlists.SOURCES) IO.execute { // so that the public playlists can be searched too
            try { Playlists.load(app, s) } catch (e: Exception) { Log.w(TAG, "search ${s.name}: $e") }
        }
    }

    /** Results while typing; [submit] (the keyboard's search key) also puts the remote on them. */
    private fun runSearch(q: String, submit: Boolean) {
        searchTask?.let(ui::removeCallbacks)
        query = q
        val m = menu ?: return
        val onSearch = rail.getOrNull(railPos)?.key == "search"
        if (q.trim().length < 2) {
            results = emptyList()
            if (onSearch) openRail(railPos)
            return
        }
        IO.execute {
            val r = try { m.search(q) } catch (e: Exception) { Log.w(TAG, "search: $e"); emptyList() }
            ui.post {
                if (query != q || isDestroyed) return@post
                results = r
                if (rail.getOrNull(railPos)?.key == "search") {
                    openRail(railPos)
                    if (submit && r.isNotEmpty()) focus(Col.GROUPS)
                }
            }
        }
    }

    private fun keyboard(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT) else imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    // ---------- remote ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (menu == null) return e.keyCode != KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e) // start screen
        return if (mode == Mode.FULL) fullKey(e) else browseKey(e)
    }

    private fun fullKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val controls = playerView.isControllerFullyVisible
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!down) if (controls) playerView.hideController() else leaveFull()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (playback.stuck) { // OK after an error: retry or the other player
                if (!down) playback.ok()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) zapBy(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) zapBy(-1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!controls && zap.size > 1) { if (down) zapBy(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls && zap.size > 1) { if (down) zapBy(-1); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { if (down) playback.channel?.let(::banner); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { if (down) stopPlayback(); return true }
        }
        return playerView.dispatchKeyEvent(e) || super.dispatchKeyEvent(e)
    }

    private fun browseKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val k = e.keyCode
        if (searchBox.hasFocus()) return searchKey(e)
        when (k) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!down && !e.isCanceled && !goBack()) finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) left(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) right(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = k == KeyEvent.KEYCODE_DPAD_UP
                val (pos, n) = position()
                if (down && up && pos <= 0 && focusCol == Col.GROUPS && searchBox.visibility == View.VISIBLE) { focus(Col.SEARCH); return true }
                if (down && (up && pos <= 0 || !up && pos >= n - 1)) return true // stays in its column
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { if (down && e.repeatCount == 0) current()?.let(::toggleFavorite); return true }
            KeyEvent.KEYCODE_INFO -> { if (down) (current() as? Channel)?.let(::openProgs); return true }
            KeyEvent.KEYCODE_GUIDE -> {
                if (down) rail.indexOfFirst { it.key == "guide" }.takeIf { it >= 0 }?.let { railFocus = it; openRail(it); focus(Col.GROUPS) }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) page(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) page(1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (down && e.repeatCount == 0) {
                    if (mode == Mode.PREVIEW) setMode(Mode.FULL)
                    else Store.history().firstOrNull { it is Channel }?.let { play(it as Channel, listOf(it)) } // the last channel watched
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { if (down && mode == Mode.PREVIEW) stopPlayback(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun searchKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && groupRows.itemCount > 0) focus(Col.GROUPS); return true }
            KeyEvent.KEYCODE_DPAD_UP -> return true
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && groupRows.itemCount == 0 && itemRows.itemCount > 0) focus(Col.ITEMS); return searchBox.selectionEnd >= searchBox.length() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (searchBox.selectionStart <= 0) { if (down) focus(Col.RAIL); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!down) focus(Col.RAIL); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (!down) keyboard(true); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Position and size of the focused column. */
    private fun position(): Pair<Int, Int> = when (focusCol) {
        Col.RAIL -> railFocus to rail.size
        Col.GROUPS -> (col2?.pos ?: 0) to groupRows.itemCount
        Col.ITEMS -> (col3?.pos ?: 0) to itemRows.itemCount
        Col.PROGS -> progPos to progRows.itemCount
        Col.SEARCH -> 0 to 1
    }

    private fun left() {
        when (focusCol) {
            Col.PROGS -> { hideProgs(); focus(Col.ITEMS) }
            Col.ITEMS -> if (!focus(Col.GROUPS)) focus(Col.RAIL)
            Col.GROUPS, Col.SEARCH -> focus(Col.RAIL)
            Col.RAIL -> {}
        }
    }

    private fun right() {
        when (focusCol) {
            Col.RAIL -> { flushRail(); focus(Col.GROUPS) }
            Col.GROUPS -> { flushGroup(); focus(Col.ITEMS) }
            Col.ITEMS -> (current() as? Channel)?.let(::openProgs)
            else -> {}
        }
    }

    /** Back: programme → content → one level up → groups → menu; from the menu it stops the preview, then leaves. */
    private fun goBack(): Boolean {
        when (focusCol) {
            Col.PROGS -> { hideProgs(); focus(Col.ITEMS) }
            Col.ITEMS -> if (back.isNotEmpty()) pop(Col.ITEMS) else if (!focus(Col.GROUPS)) focus(Col.RAIL)
            Col.GROUPS, Col.SEARCH -> if (back.isNotEmpty()) pop(Col.GROUPS) else focus(Col.RAIL)
            Col.RAIL -> if (mode == Mode.PREVIEW) {
                stopPlayback()
                Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
            } else return false
        }
        return true
    }

    /** CH+ / CH− in a list: ten rows at a time. */
    private fun page(d: Int) {
        val (pos, n) = position()
        if (n == 0) return
        val p = (pos + d * 10).coerceIn(0, n - 1)
        when (focusCol) {
            Col.GROUPS -> focusRow(groupList, p)
            Col.ITEMS -> focusRow(itemList, p)
            Col.PROGS -> focusRow(progList, p)
            else -> {}
        }
    }

    // ---------- lifecycle ----------

    override fun onStart() {
        super.onStart()
        if (resumeFull && playback.channel != null) {
            resumeFull = false
            playback.resume()
            setMode(Mode.FULL)
        }
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = onStore
        if (menu != null) onStore()
    }

    override fun onPause() {
        if (Store.onChange === onStore) Store.onChange = null
        super.onPause()
    }

    override fun onStop() {
        resumeFull = mode == Mode.FULL
        when (mode) {
            Mode.FULL -> playback.suspend()
            Mode.PREVIEW -> stopPlayback()
            Mode.NONE -> {}
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        playback.stop()
        super.onDestroy()
    }
}
