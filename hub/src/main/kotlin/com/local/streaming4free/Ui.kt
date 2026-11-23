package com.local.streaming4free

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm", Locale.ROOT)

fun hm(t: Long): String = synchronized(CLOCK) { CLOCK.format(Date(t)) }
fun range(p: Guide.Prog) = hm(p.start) + " – " + hm(p.stop)

/** The rows of one column: the rail, the groups, the content or a channel's programme. */
class Rows(private val layout: Int, private val bind: (View, Item, Int) -> Unit, private val onFocus: (Int) -> Unit,
           private val onClick: (Int) -> Unit, private val onLong: (Int) -> Unit) : RecyclerView.Adapter<Rows.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v)

    var items: List<Item> = emptyList()
        private set
    /** The row this column has open (Kodi's "active" edge); -1 for none. */
    var active = -1
        private set
    private var rv: RecyclerView? = null

    override fun onAttachedToRecyclerView(r: RecyclerView) { rv = r }

    fun set(list: List<Item>, active: Int) {
        items = list
        this.active = active
        notifyDataSetChanged()
    }

    fun activate(p: Int) {
        if (p == active) return
        val old = active
        active = p
        rv?.findViewHolderForAdapterPosition(old)?.itemView?.isActivated = false
        rv?.findViewHolderForAdapterPosition(p)?.itemView?.isActivated = true
    }

    /** Rebinds the rows on screen (favorite badge, dead stream, playing marker, guide), not the ones off screen. */
    fun redraw() = notifyItemRangeChanged(0, itemCount)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val h = Holder(v)
        v.setOnFocusChangeListener { _, has -> if (has) h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onFocus) }
        v.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        v.setOnLongClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLong); true } // long press on OK
        return h
    }

    override fun onBindViewHolder(h: Holder, p: Int) {
        h.itemView.isActivated = p == active
        bind(h.itemView, items[p], p)
    }

    override fun onViewRecycled(h: Holder) {
        h.itemView.findViewById<ImageView?>(R.id.logo)?.dispose()
    }
}

/** A channel row or card image: its logo, else the TV placeholder. */
fun ImageView.logo(url: String) = load(url.ifEmpty { null }) {
    placeholder(R.drawable.ph_tv)
    error(R.drawable.ph_tv)
    fallback(R.drawable.ph_tv)
}

fun ImageView.appArt(c: Context, app: App) {
    dispose()
    setImageDrawable(when {
        app.site != null -> ContextCompat.getDrawable(c, if (app.id == "watched") R.drawable.banner_watched else R.drawable.banner_cinefuse) // their former tiles
        else -> app.installedPackage(c.packageManager)?.let { p -> c.packageManager.getApplicationBanner(p) ?: c.packageManager.getApplicationIcon(p) }
            ?: ContextCompat.getDrawable(c, R.drawable.ph_app)
    })
}

fun TextView.show(text: CharSequence?) {
    this.text = text
    visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
}

fun ProgressBar.show(percent: Int?) {
    visibility = if (percent == null) View.INVISIBLE else View.VISIBLE
    progress = percent ?: 0
}

/** OK on an app: built-in site, installed app, or its Play Store page (one press on Install). */
fun openApp(a: Activity, app: App) {
    val launch = app.launchIntent(a)
    when {
        launch != null -> {
            Store.addHistory(app)
            a.startActivity(if (app.site != null) launch else launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        app.onPlayStore -> try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + app.packages[0])))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, R.string.no_store, Toast.LENGTH_LONG).show()
        }
        else -> Toast.makeText(a, R.string.not_installed, Toast.LENGTH_LONG).show()
    }
}

/** YouTube pages go to SmartTube when installed (no ads), else to the YouTube app: Google allows no other player. */
fun openYouTube(a: Activity, ch: Channel) {
    val pm = a.packageManager
    val pkg = APPS.filter { it.id == "smarttube" || it.id == "youtube" }.firstNotNullOfOrNull { it.installedPackage(pm) }
    if (pkg == null) {
        Toast.makeText(a, R.string.no_youtube, Toast.LENGTH_LONG).show()
        return
    }
    if (!ch.url.contains("/results?")) Store.addHistory(ch)
    try {
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(a, R.string.no_youtube, Toast.LENGTH_LONG).show()
    }
}
