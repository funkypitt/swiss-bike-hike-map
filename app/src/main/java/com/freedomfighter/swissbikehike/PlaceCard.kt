package com.freedomfighter.swissbikehike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.DialogPlaceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.util.Locale

/** What the card knows about a place, a business or a person: from the places pack, the directory, or both. */
class PlaceInfo(
    val name: String,
    val at: LatLng?,
    val category: String = "",
    val hours: String? = null,
    var phone: String? = null,
    val web: String? = null,
    val mail: String? = null,
    var street: String = "",
    var zip: String = "",
    var city: String = "",
    val more: List<String> = emptyList(),
    val person: Boolean = false,
    /** From the places pack: the directory may complete it. */
    val fromPack: Boolean = false,
) {
    val address: String get() = listOf(street, listOf(zip, city).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")

    companion object {
        fun of(ctx: Context, p: Place): PlaceInfo {
            val x = p.extras
            val more = ArrayList<String>()
            x["cuisine"]?.let { more.add(it.replace('_', ' ').replace(';', ',')) }
            x["type"]?.let { more.add(it.replace('_', ' ')) }
            x["ele"]?.toDoubleOrNull()?.let { more.add(String.format(Locale.US, "%.0f m", it)) }
            x["cap"]?.let { more.add(ctx.getString(R.string.place_capacity, it)) }
            if (x["fee"] == "yes") more.add(ctx.getString(R.string.place_fee))
            x["op"]?.let { more.add(it) }
            return PlaceInfo(p.label(ctx), p.at, Places.categoryText(ctx, p), x["oh"], x["tel"], x["web"], x["mail"],
                x["addr"] ?: "", x["pc"] ?: "", x["city"] ?: "", more, fromPack = true)
        }

        fun of(h: Directory.Hit, at: LatLng? = null): PlaceInfo =
            PlaceInfo(h.name, at, "", phone = h.phone.ifBlank { null }, street = h.street, zip = h.zip, city = h.city, person = h.person)
    }
}

/**
 * The card of a place: name, kind, open or closed now with the week's hours, address, phone,
 * e-mail, website, position; then CALL (the phone app, the number typed in), CONTACT (the
 * contacts app's new-contact form, filled in), COPY, WEBSITE, SHARE and SEARCH.CH (completes the
 * place with the directory, when a key is set). `primary` is the caller's own action (pin it,
 * show it on the map).
 */
object PlaceCard {
    fun show(act: AppCompatActivity, info: PlaceInfo, from: LatLng? = null, primary: Pair<Int, () -> Unit>? = null) {
        val v = DialogPlaceBinding.inflate(act.layoutInflater)
        val dlg = AlertDialog.Builder(act).setView(v.root).setNegativeButton(R.string.close, null)
        primary?.let { (label, run) -> dlg.setPositiveButton(label) { _, _ -> run() } }
        val d = dlg.create()
        fill(act, v, info, from)
        v.pCall.setOnClickListener { info.phone?.let { dial(act, it) } }
        v.pContact.setOnClickListener { addContact(act, info) }
        v.pCopy.setOnClickListener { copy(act, info) }
        v.pWeb.setOnClickListener { info.web?.let { w -> runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (w.startsWith("http")) w else "https://$w"))) } } }
        v.pShare.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text(act, info)).putExtra(Intent.EXTRA_SUBJECT, info.name)
            runCatching { act.startActivity(Intent.createChooser(send, null)) }
        }
        v.pDirectory.setOnClickListener { complete(act, v, info, from) }
        d.show()
    }

    private fun fill(act: AppCompatActivity, v: DialogPlaceBinding, info: PlaceInfo, from: LatLng?) {
        v.pTitle.text = info.name
        v.pCategory.text = info.category
        v.pCategory.visibility = if (info.category.isBlank() || info.category == info.name) View.GONE else View.VISIBLE
        val week = OpeningHours.parse(info.hours)
        if (week != null) {
            val st = OpeningHours.status(week)
            v.pStatus.text = statusText(act, st)
            v.pStatus.setTextColor(ContextCompat.getColor(act, if (st.open) R.color.transit_green else R.color.bike_red))
            v.pStatus.visibility = View.VISIBLE
            v.pHours.text = if (week.always) "" else OpeningHours.table(week, act.getString(R.string.place_closed_day)).joinToString("\n")
            v.pHours.visibility = if (week.always) View.GONE else View.VISIBLE
        } else {
            v.pStatus.visibility = View.GONE
            v.pHours.text = info.hours ?: ""
            v.pHours.visibility = if (info.hours.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val lines = listOfNotNull(info.address.ifBlank { null }, info.phone, info.mail, info.web?.let { tidyWeb(it) }) + info.more
        v.pLines.text = lines.joinToString("\n")
        v.pLines.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        v.pWhere.text = info.at?.let { p ->
            Favourites.coordsName(p.latitude, p.longitude) + (from?.let { "   ·   " + Fmt.dist(Places.distance(it.latitude, it.longitude, p.latitude, p.longitude)) } ?: "")
        } ?: ""
        v.pWhere.visibility = if (info.at == null) View.GONE else View.VISIBLE
        v.pCall.isEnabled = !info.phone.isNullOrBlank()
        v.pWeb.isEnabled = !info.web.isNullOrBlank()
        v.pDirectory.visibility = if (info.fromPack && info.name.isNotBlank() && Directory.enabled(act)) View.VISIBLE else View.INVISIBLE
    }

    fun statusText(ctx: Context, st: OpeningHours.Status): String {
        if (st.open && st.inDays < 0) return ctx.getString(R.string.place_open_always)
        if (!st.open && st.inDays < 0) return ctx.getString(R.string.place_closed)
        val clock = OpeningHours.clock(st.minute)
        val day = when (st.inDays) {
            0 -> null
            1 -> ctx.getString(R.string.place_tomorrow)
            else -> {
                val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
                OpeningHours.dayNames()[(today + st.inDays) % 7]
            }
        }
        return if (st.open) { if (day == null) ctx.getString(R.string.place_open_until, clock) else ctx.getString(R.string.place_open_until_day, day, clock) }
        else { if (day == null) ctx.getString(R.string.place_closed_opens, clock) else ctx.getString(R.string.place_closed_opens_day, day, clock) }
    }

    /** One short line for a list: « Open · closes at 19:00 », or null when the hours say nothing readable. */
    fun statusLine(ctx: Context, hours: String?): String? = OpeningHours.parse(hours)?.let { statusText(ctx, OpeningHours.status(it)) }

    private fun tidyWeb(w: String) = w.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

    fun dial(ctx: Context, tel: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.split(';').first().replace(" ", "")))) }
            .onFailure { Toast.makeText(ctx, R.string.place_no_app, Toast.LENGTH_SHORT).show() }
    }

    /** The contacts app's « new contact » form, filled in: the person saves (or not) there. No permission needed. */
    fun addContact(ctx: Context, info: PlaceInfo) {
        val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, info.name)
        if (!info.person) i.putExtra(ContactsContract.Intents.Insert.COMPANY, info.name)
        info.phone?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            i.putExtra(ContactsContract.Intents.Insert.PHONE, it)
            i.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, if (info.person) ContactsContract.CommonDataKinds.Phone.TYPE_MAIN else ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
        }
        info.mail?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        if (info.address.isNotBlank()) i.putExtra(ContactsContract.Intents.Insert.POSTAL, info.address)
        val data = ArrayList<ContentValues>()
        info.web?.let { w ->
            data.add(ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, w)
                put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
            })
        }
        if (data.isNotEmpty()) i.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
        val notes = listOfNotNull(info.category.ifBlank { null }, hoursText(ctx, info)).joinToString("\n")
        if (notes.isNotBlank()) i.putExtra(ContactsContract.Intents.Insert.NOTES, notes)
        runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, R.string.place_no_app, Toast.LENGTH_SHORT).show() }
    }

    private fun hoursText(ctx: Context, info: PlaceInfo): String? {
        val w = OpeningHours.parse(info.hours) ?: return info.hours
        return if (w.always) ctx.getString(R.string.place_open_always) else OpeningHours.table(w, ctx.getString(R.string.place_closed_day)).joinToString("\n")
    }

    /** Everything on the card as plain text, with a map link. */
    fun text(ctx: Context, info: PlaceInfo): String {
        val pos = info.at?.let {
            val lat = String.format(Locale.US, "%.5f", it.latitude); val lon = String.format(Locale.US, "%.5f", it.longitude)
            "$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon"
        }
        return listOfNotNull(info.name, info.category.ifBlank { null }?.takeIf { it != info.name }, info.address.ifBlank { null },
            info.phone, info.mail, info.web, hoursText(ctx, info), pos).joinToString("\n")
    }

    private fun copy(ctx: Context, info: PlaceInfo) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(info.name, text(ctx, info)))
        Toast.makeText(ctx, R.string.place_copied, Toast.LENGTH_SHORT).show()
    }

    /** Asks search.ch for this place (name and town); the phone and address of the best entry fill the card. */
    private fun complete(act: AppCompatActivity, v: DialogPlaceBinding, info: PlaceInfo, from: LatLng?) {
        v.pDirectory.isEnabled = false
        act.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Directory.search(act, info.name, info.city) } }
            v.pDirectory.isEnabled = true
            found.onFailure { e -> Toast.makeText(act, (e.message ?: "no answer").take(120), Toast.LENGTH_LONG).show() }
            found.onSuccess { list ->
                val fn = Transit.fold(info.name)
                val best = list.filter { !it.person }.firstOrNull { e -> val fe = Transit.fold(e.name); fe.contains(fn) || fn.contains(fe) }
                    ?: list.firstOrNull { it.phone.isNotBlank() }
                if (best == null) { Toast.makeText(act, R.string.place_directory_none, Toast.LENGTH_LONG).show(); return@onSuccess }
                if (info.phone.isNullOrBlank()) info.phone = best.phone.ifBlank { null }
                if (info.street.isBlank()) { info.street = best.street; info.zip = best.zip; info.city = best.city.ifBlank { info.city } }
                fill(act, v, info, from)
                Toast.makeText(act, Directory.CREDIT, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
