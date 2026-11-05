package com.freedomfighter.swissbikehike

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.freedomfighter.swissbikehike.databinding.ActivityStartBinding

/** The first screen: BIKE, WALK or TRANSIT, nothing else. A running recording goes straight to the map. */
class StartActivity : AppCompatActivity() {
    private lateinit var b: ActivityStartBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStartBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        b.bikeBlock.setOnClickListener { open(Mode.BIKE) }
        b.walkBlock.setOnClickListener { open(Mode.WALK) }
        b.transitBlock.setOnClickListener { open(Mode.TRANSIT) }
        b.offHint.setOnClickListener { startActivity(Intent(this, MenuActivity::class.java)) }
        b.footer.text = getString(R.string.attribution) + " · " + BuildConfig.VERSION_NAME
        // Nothing starts by itself here: every download is asked for in the menu and followed there
        // and in its notification. (Refreshing the saved areas at launch used to stall the map: the
        // whole-country pack being marked stale in the tile database while the map reads it.)
    }

    override fun onResume() {
        super.onResume()
        val rec = RecordService.state.value
        val nav = NavService.state.value
        if (nav.running) open(nav.mode) else if (rec.running) open(rec.mode)
        // a reminder from the dates on the phone only (no network): which offline parts are worth a look
        val stopped = Offline.wanted(this).isNotEmpty() && (Offline.paused(this) || !Offline.isActive(this))
        val due = if (Offline.wanted(this).isEmpty()) Offline.dueLocally(this) else emptyList()
        b.offHint.visibility = if (due.isEmpty() && !stopped) View.GONE else View.VISIBLE
        b.offHint.text = if (stopped) getString(R.string.off_hint_paused, Offline.overall(this))
            else getString(R.string.off_hint, due.joinToString(", ") { getString(it.label).lowercase(java.util.Locale.getDefault()) })
    }

    private fun open(mode: Mode) {
        Prefs(this).mode = mode
        startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_MODE, mode.name))
    }
}
